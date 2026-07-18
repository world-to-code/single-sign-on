package com.example.sso.oidc.internal.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.oidc.BackChannelLogout;
import com.example.sso.oidc.OidcBackchannelSessionIndex;
import com.example.sso.oidc.OidcParticipation;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for the portal-facing OIDC participant read + single-participant logout: capability flagging by
 * back-channel URI, one-client logout removes ONLY that client (session and other apps survive), and a client
 * removed since token-issue is skipped rather than shown.
 */
@ExtendWith(MockitoExtension.class)
class OidcParticipantSessionsImplTest {

    private static final String SID = "sid-1";
    private static final String USER = "bob";

    @Mock
    OidcBackchannelSessionIndex index;
    @Mock
    RegisteredClientRepository clients;
    @Mock
    OidcBackchannelDelivery delivery;
    @Mock
    AuditService audit;

    private OidcParticipantSessionsImpl sessions;

    @BeforeEach
    void setUp() {
        sessions = new OidcParticipantSessionsImpl(index, clients, delivery, audit);
    }

    private RegisteredClient client(String clientId, String name, boolean backchannel) {
        ClientSettings.Builder settings = ClientSettings.builder();
        if (backchannel) {
            settings.setting(BackChannelLogout.CLIENT_SETTING_URI, "https://rp.example.com/bcl");
        }
        return RegisteredClient.withId(clientId).clientId(clientId).clientName(name)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://rp.example.com/cb").scope(OidcScopes.OPENID)
                .clientSettings(settings.build()).build();
    }

    @Test
    void participationsFlagOneClickCapabilityByBackChannelUri() {
        when(index.lookup(SID))
                .thenReturn(new OidcBackchannelSessionIndex.Participants(USER, Set.of("c-bcl", "c-plain")));
        RegisteredClient bcl = client("c-bcl", "Billing", true);
        RegisteredClient plain = client("c-plain", "Wiki", false);
        when(clients.findById("c-bcl")).thenReturn(bcl);
        when(clients.findById("c-plain")).thenReturn(plain);
        when(delivery.supportsBackChannelLogout(bcl)).thenReturn(true);
        when(delivery.supportsBackChannelLogout(plain)).thenReturn(false);

        assertThat(sessions.participationsFor(Set.of(SID)))
                .extracting(OidcParticipation::registeredClientId, OidcParticipation::name,
                        OidcParticipation::backChannelLogoutSupported)
                .containsExactlyInAnyOrder(
                        tuple("c-bcl", "Billing", true),
                        tuple("c-plain", "Wiki", false));
    }

    @Test
    void aClientRemovedSinceTokenIssueIsSkipped() {
        when(index.lookup(SID)).thenReturn(new OidcBackchannelSessionIndex.Participants(USER, Set.of("c-gone")));
        when(clients.findById("c-gone")).thenReturn(null);

        assertThat(sessions.participationsFor(Set.of(SID))).isEmpty();
    }

    @Test
    void logoutDeliversOneTokenAndRemovesOnlyThatClient() {
        when(delivery.deliver("c-bcl", USER, SID)).thenReturn(BackchannelDeliveryOutcome.DELIVERED);

        sessions.logout(SID, "c-bcl", USER);

        verify(delivery).deliver("c-bcl", USER, SID);
        verify(index).removeParticipants(SID, Set.of("c-bcl")); // ONLY this client; the rest of the sid survives
        verify(index, never()).clear(any()); // the session index is not wiped
        verify(audit).record(argThat(r -> r != null && r.success()
                && r.detail().contains("c-bcl") && r.detail().contains("self-service")));
    }

    @Test
    void logoutOfATransientlyFailedClientKeepsItInTheIndexForTheDurableBackstop() {
        // A temporarily-unreachable RP: audit the failure but DO NOT remove the client — a later whole-session
        // termination must still find it and re-drive its logout (clear-only-settled, like the fan-out path).
        when(delivery.deliver("c-bcl", USER, SID)).thenReturn(BackchannelDeliveryOutcome.TRANSIENT);

        sessions.logout(SID, "c-bcl", USER);

        verify(index, never()).removeParticipants(any(), any());
        verify(audit).record(argThat((AuditRecord r) -> r != null && !r.success()));
    }

    @Test
    void logoutOfATerminallyGoneClientRemovesIt() {
        // The client was deleted since the token was issued (TERMINAL): nothing to re-drive, so settle + remove.
        when(delivery.deliver("c-gone", USER, SID)).thenReturn(BackchannelDeliveryOutcome.TERMINAL);

        sessions.logout(SID, "c-gone", USER);

        verify(index).removeParticipants(SID, Set.of("c-gone"));
    }
}
