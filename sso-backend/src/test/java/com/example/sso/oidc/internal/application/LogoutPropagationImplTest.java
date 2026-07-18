package com.example.sso.oidc.internal.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.logoutretry.LogoutRetryCoordinator;
import com.example.sso.oidc.BackChannelLogout;
import com.example.sso.oidc.OidcBackchannelSessionIndex;
import com.example.sso.organization.OrganizationService;
import com.example.sso.tenancy.OrgContext;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OIDC back-channel logout classification — the clear-only-delivered contract. A delivered or terminally
 * undeliverable client (gone, or not a back-channel client) is settled and removed; a transient POST failure
 * is kept in the index for the durable retry sweep, so a temporarily-down RP no longer loses the logout.
 */
@ExtendWith(MockitoExtension.class)
class LogoutPropagationImplTest {

    private static final String SID = "sid-1";
    private static final String USER = "bob";
    private static final String REFUSED_URI = "http://127.0.0.1:1/backchannel";

    @Mock
    OidcBackchannelSessionIndex index;
    @Mock
    RegisteredClientRepository clients;
    @Mock
    LogoutTokenFactory tokens;
    @Mock
    AuditService audit;
    @Mock
    OrgContext orgContext;
    @Mock
    OrganizationService organizations;
    @Mock
    LogoutRetryCoordinator retryCoordinator;
    @Mock
    JdbcTemplate jdbc;

    private LogoutPropagationImpl propagation;

    @BeforeEach
    void setUp() {
        OidcBackchannelDelivery delivery = new OidcBackchannelDelivery(clients, tokens, orgContext, organizations,
                jdbc, "http://localhost:9000", Duration.ofSeconds(2));
        propagation = new LogoutPropagationImpl(index, audit, retryCoordinator, delivery);
        // jdbc is left unstubbed: clientOrg(...) returns null (a global client), so the delivery runs without
        // an org context and never touches organizations/orgContext — keeping these classification tests focused.
        lenient().when(tokens.create(any(), any(), any(), any())).thenReturn("logout-token");
    }

    private RegisteredClient bclClient(String clientId, String backchannelUri) {
        return baseClient(clientId)
                .clientSettings(ClientSettings.builder()
                        .setting(BackChannelLogout.CLIENT_SETTING_URI, backchannelUri).build())
                .build();
    }

    private RegisteredClient nonBclClient(String clientId) {
        return baseClient(clientId).clientSettings(ClientSettings.builder().build()).build();
    }

    private RegisteredClient.Builder baseClient(String clientId) {
        return RegisteredClient.withId(clientId)
                .clientId(clientId)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://rp.example.com/callback")
                .scope(OidcScopes.OPENID);
    }

    private void participants(String... clientIds) {
        when(index.lookup(SID)).thenReturn(new OidcBackchannelSessionIndex.Participants(USER, Set.of(clientIds)));
    }

    @Test
    void anUnregisteredClientIsTerminalAndSettled() {
        participants("c-gone");
        when(clients.findById("c-gone")).thenReturn(null);
        when(index.removeParticipants(eq(SID), any())).thenReturn(0);

        propagation.propagate(SID, USER);

        verify(index).removeParticipants(SID, Set.of("c-gone"));
        verify(retryCoordinator).reschedule(eq(OidcLogoutRetryDriver.RETRY_QUEUE), eq(SID), eq(USER), eq(false), any());
    }

    @Test
    void aClientNotConfiguredForBackChannelLogoutIsTerminal() {
        participants("c-plain");
        when(clients.findById("c-plain")).thenReturn(nonBclClient("c-plain"));
        when(index.removeParticipants(eq(SID), any())).thenReturn(0);

        propagation.propagate(SID, USER);

        verify(index).removeParticipants(SID, Set.of("c-plain"));
    }

    @Test
    void aTransientPostFailureIsKeptForRetryNotSettled() {
        participants("c-down");
        when(clients.findById("c-down")).thenReturn(bclClient("c-down", REFUSED_URI));
        when(index.removeParticipants(eq(SID), any())).thenReturn(1);

        propagation.propagate(SID, USER);

        ArgumentCaptor<Set<String>> settled = ArgumentCaptor.captor();
        verify(index).removeParticipants(eq(SID), settled.capture());
        assertThat(settled.getValue()).doesNotContain("c-down");
        verify(retryCoordinator).reschedule(eq(OidcLogoutRetryDriver.RETRY_QUEUE), eq(SID), eq(USER), eq(true), any());
    }

    @Test
    void onlyTheTransientClientSurvivesAMixedPass() {
        participants("c-gone", "c-down");
        when(clients.findById("c-gone")).thenReturn(null);
        when(clients.findById("c-down")).thenReturn(bclClient("c-down", REFUSED_URI));
        when(index.removeParticipants(eq(SID), any())).thenReturn(1);

        propagation.propagate(SID, USER);

        ArgumentCaptor<Set<String>> settled = ArgumentCaptor.captor();
        verify(index).removeParticipants(eq(SID), settled.capture());
        assertThat(settled.getValue()).containsExactly("c-gone"); // terminal settled, transient kept
    }

    @Test
    void aSuccessfullyDeliveredClientIsSettledAndNotRetried() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/bcl", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            String uri = "http://127.0.0.1:" + server.getAddress().getPort() + "/bcl";
            participants("c-ok");
            when(clients.findById("c-ok")).thenReturn(bclClient("c-ok", uri));
            when(index.removeParticipants(eq(SID), any())).thenReturn(0);

            propagation.propagate(SID, USER);

            ArgumentCaptor<Set<String>> settled = ArgumentCaptor.captor();
            verify(index).removeParticipants(eq(SID), settled.capture());
            assertThat(settled.getValue()).contains("c-ok"); // delivered → settled, removed from the index
            verify(retryCoordinator).reschedule(eq(OidcLogoutRetryDriver.RETRY_QUEUE), eq(SID), eq(USER), eq(false), any());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void anInfraFaultResolvingAClientKeepsItForRetryNotSettled() {
        // A DB blip during fan-out (here: the client registry lookup) must not drop the logout — the client
        // stays in the index and the sweep re-drives it, and the loop still reaches reschedule.
        participants("c-db");
        when(clients.findById("c-db")).thenThrow(new QueryTimeoutException("pool exhausted"));
        when(index.removeParticipants(eq(SID), any())).thenReturn(1);

        propagation.propagate(SID, USER);

        ArgumentCaptor<Set<String>> settled = ArgumentCaptor.captor();
        verify(index).removeParticipants(eq(SID), settled.capture());
        assertThat(settled.getValue()).doesNotContain("c-db");
        verify(retryCoordinator).reschedule(eq(OidcLogoutRetryDriver.RETRY_QUEUE), eq(SID), eq(USER), eq(true), any());
    }

    @Test
    void givingUpAuditsEveryStillUndeliveredClientAndClears() {
        participants("c-down");
        when(clients.findById("c-down")).thenReturn(bclClient("c-down", REFUSED_URI));
        when(index.removeParticipants(eq(SID), any())).thenReturn(1);

        ArgumentCaptor<Runnable> onGiveUp = ArgumentCaptor.captor();
        propagation.propagate(SID, USER);
        verify(retryCoordinator).reschedule(any(), any(), any(), anyBoolean(), onGiveUp.capture());

        // At give-up time two clients are still undelivered — EACH must be audited as abandoned before the clear.
        when(index.lookup(SID)).thenReturn(
                new OidcBackchannelSessionIndex.Participants(USER, Set.of("c-down", "c-other")));
        onGiveUp.getValue().run();

        verify(audit).record(argThat(abandonedFor("c-down")));
        verify(audit).record(argThat(abandonedFor("c-other")));
        verify(index).clear(SID);
    }

    @Test
    void nothingToDeliverTellsTheCoordinatorThereIsNoRemainder() {
        when(index.lookup(SID)).thenReturn(new OidcBackchannelSessionIndex.Participants(USER, Set.of()));
        when(index.removeParticipants(SID, Set.of())).thenReturn(0);

        propagation.propagate(SID, USER);

        verify(retryCoordinator).reschedule(eq(OidcLogoutRetryDriver.RETRY_QUEUE), eq(SID), eq(USER), eq(false), any());
        verify(index, never()).clear(SID);
    }

    private static ArgumentMatcher<AuditRecord> abandonedFor(String clientId) {
        return r -> r != null && !r.success() && r.detail() != null
                && r.detail().contains(clientId) && r.detail().contains("abandoned");
    }
}
