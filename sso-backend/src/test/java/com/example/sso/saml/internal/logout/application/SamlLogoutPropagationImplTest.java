package com.example.sso.saml.internal.logout.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.logoutretry.LogoutRetryCoordinator;
import com.example.sso.saml.internal.relyingparty.domain.SamlRelyingParty;
import com.example.sso.saml.internal.relyingparty.domain.SamlRelyingPartyRepository;
import com.example.sso.saml.relyingparty.SloBinding;
import com.example.sso.tenancy.OrgContext;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SAML SLO propagation classification — the clear-only-delivered contract. A delivered or terminally
 * undeliverable SP is settled (removed from the index); a transient SOAP failure is kept for the durable
 * retry sweep. The load-bearing regression: a FRONT-CHANNEL SP is terminal here (the browser-less path can
 * never reach it) and must NEVER be kept for retry, or the sweep would re-drive it forever.
 */
@ExtendWith(MockitoExtension.class)
class SamlLogoutPropagationImplTest {

    private static final String SID = "sid-1";
    private static final String USER = "carol";

    @Mock
    SamlSloSessionIndex index;
    @Mock
    SamlRelyingPartyRepository relyingParties;
    @Mock
    SamlLogoutMessageBuilder messageBuilder;
    @Mock
    AuditService audit;
    @Mock
    OrgContext orgContext;
    @Mock
    LogoutRetryCoordinator retryCoordinator;

    private SamlLogoutPropagationImpl propagation;

    @BeforeEach
    void setUp() {
        propagation = new SamlLogoutPropagationImpl(index, relyingParties, messageBuilder, audit, orgContext,
                retryCoordinator, Duration.ofSeconds(2));
        // callAsPlatform just runs the RP lookup in this test.
        lenient().when(orgContext.callAsPlatform(any())).thenAnswer(inv -> inv.<Supplier<?>>getArgument(0).get());
    }

    private SamlRelyingParty rp(String url, SloBinding binding) {
        SamlRelyingParty rp = mock(SamlRelyingParty.class);
        lenient().when(rp.getSingleLogoutUrl()).thenReturn(url);
        lenient().when(rp.sloBinding()).thenReturn(binding);
        lenient().when(rp.getOrgId()).thenReturn(null);
        return rp;
    }

    @Test
    void aFrontChannelSpIsTerminalNotedNotDeliveredAndNeverRetried() {
        SamlRelyingParty frontChannel = rp("https://sp/slo", SloBinding.REDIRECT);
        when(index.lookup(SID)).thenReturn(Map.of("sp-fc", "nameid"));
        when(relyingParties.findByEntityId("sp-fc")).thenReturn(Optional.of(frontChannel));
        when(index.removeParticipants(eq(SID), any())).thenReturn(0);

        propagation.propagate(SID, USER);

        verify(audit).record(argThat(frontChannelNoteFor("sp-fc")));
        // Settled (removed), so nothing to keep — and the retry coordinator is told there is no remainder.
        verify(index).removeParticipants(SID, Set.of("sp-fc"));
        verify(retryCoordinator).reschedule(eq(SamlLogoutRetryDriver.RETRY_QUEUE), eq(SID), eq(USER), eq(false), any());
    }

    @Test
    void anSpWithoutAnSloEndpointIsTerminal() {
        SamlRelyingParty noEndpoint = rp("  ", SloBinding.SOAP);
        when(index.lookup(SID)).thenReturn(Map.of("sp-x", "nameid"));
        when(relyingParties.findByEntityId("sp-x")).thenReturn(Optional.of(noEndpoint));
        when(index.removeParticipants(eq(SID), any())).thenReturn(0);

        propagation.propagate(SID, USER);

        verify(index).removeParticipants(SID, Set.of("sp-x"));
    }

    @Test
    void anUnknownRelyingPartyIsTerminal() {
        when(index.lookup(SID)).thenReturn(Map.of("sp-gone", "nameid"));
        when(relyingParties.findByEntityId("sp-gone")).thenReturn(Optional.empty());
        when(index.removeParticipants(eq(SID), any())).thenReturn(0);

        propagation.propagate(SID, USER);

        verify(index).removeParticipants(SID, Set.of("sp-gone"));
    }

    @Test
    void aTransientSoapFailureIsKeptForRetryNotSettled() {
        SamlRelyingParty soap = rp("http://127.0.0.1:1/slo", SloBinding.SOAP);
        when(index.lookup(SID)).thenReturn(Map.of("sp-soap", "nameid"));
        // A SOAP SP whose endpoint refuses the connection — a transient failure.
        when(relyingParties.findByEntityId("sp-soap")).thenReturn(Optional.of(soap));
        when(messageBuilder.signedLogoutRequestXml(any(), eq("nameid"), eq(SID))).thenReturn("<xml/>");
        when(index.removeParticipants(eq(SID), any())).thenReturn(1);

        propagation.propagate(SID, USER);

        // Not in the settled set — it stays in the index for the sweep.
        ArgumentCaptor<Set<String>> settled = ArgumentCaptor.captor();
        verify(index).removeParticipants(eq(SID), settled.capture());
        assertThat(settled.getValue()).doesNotContain("sp-soap");
        verify(retryCoordinator).reschedule(eq(SamlLogoutRetryDriver.RETRY_QUEUE), eq(SID), eq(USER), eq(true), any());
    }

    @Test
    void aMixedPassSettlesTheFrontChannelSpAndKeepsTheTransientSoapSp() {
        SamlRelyingParty frontChannel = rp("https://sp/slo", SloBinding.REDIRECT);
        SamlRelyingParty soap = rp("http://127.0.0.1:1/slo", SloBinding.SOAP);
        when(index.lookup(SID)).thenReturn(Map.of("sp-fc", "n1", "sp-soap", "n2"));
        when(relyingParties.findByEntityId("sp-fc")).thenReturn(Optional.of(frontChannel));
        when(relyingParties.findByEntityId("sp-soap")).thenReturn(Optional.of(soap));
        when(messageBuilder.signedLogoutRequestXml(any(), any(), any())).thenReturn("<xml/>");
        when(index.removeParticipants(eq(SID), any())).thenReturn(1);

        propagation.propagate(SID, USER);

        ArgumentCaptor<Set<String>> settled = ArgumentCaptor.captor();
        verify(index).removeParticipants(eq(SID), settled.capture());
        assertThat(settled.getValue()).containsExactly("sp-fc"); // front-channel settled, transient SOAP kept
    }

    @Test
    void aSuccessfulSoapDeliveryIsSettledAndNotRetried() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slo", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/slo";
            SamlRelyingParty soap = rp(url, SloBinding.SOAP);
            when(index.lookup(SID)).thenReturn(Map.of("sp-ok", "nameid"));
            when(relyingParties.findByEntityId("sp-ok")).thenReturn(Optional.of(soap));
            when(messageBuilder.signedLogoutRequestXml(any(), any(), any())).thenReturn("<xml/>");
            when(index.removeParticipants(eq(SID), any())).thenReturn(0);

            propagation.propagate(SID, USER);

            ArgumentCaptor<Set<String>> settled = ArgumentCaptor.captor();
            verify(index).removeParticipants(eq(SID), settled.capture());
            assertThat(settled.getValue()).contains("sp-ok"); // delivered → settled
            verify(retryCoordinator).reschedule(eq(SamlLogoutRetryDriver.RETRY_QUEUE), eq(SID), eq(USER), eq(false), any());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void anInfraFaultResolvingAnSpKeepsItForRetryNotSettled() {
        // A DB blip resolving the RP during fan-out must not drop the logout — the SP stays for the sweep.
        when(index.lookup(SID)).thenReturn(Map.of("sp-db", "nameid"));
        when(relyingParties.findByEntityId("sp-db")).thenThrow(new QueryTimeoutException("pool exhausted"));
        when(index.removeParticipants(eq(SID), any())).thenReturn(1);

        propagation.propagate(SID, USER);

        ArgumentCaptor<Set<String>> settled = ArgumentCaptor.captor();
        verify(index).removeParticipants(eq(SID), settled.capture());
        assertThat(settled.getValue()).doesNotContain("sp-db");
        verify(retryCoordinator).reschedule(eq(SamlLogoutRetryDriver.RETRY_QUEUE), eq(SID), eq(USER), eq(true), any());
    }

    @Test
    void givingUpAuditsEveryStillUndeliveredSpAndClears() {
        SamlRelyingParty soap = rp("http://127.0.0.1:1/slo", SloBinding.SOAP);
        when(index.lookup(SID)).thenReturn(Map.of("sp-soap", "nameid"));
        when(relyingParties.findByEntityId("sp-soap")).thenReturn(Optional.of(soap));
        when(messageBuilder.signedLogoutRequestXml(any(), any(), any())).thenReturn("<xml/>");
        when(index.removeParticipants(eq(SID), any())).thenReturn(1);

        ArgumentCaptor<Runnable> onGiveUp = ArgumentCaptor.captor();
        propagation.propagate(SID, USER);
        verify(retryCoordinator).reschedule(any(), any(), any(), anyBoolean(), onGiveUp.capture());

        // When the sweep later exhausts retries, EACH still-undelivered SP is audited before the index is cleared.
        when(index.lookup(SID)).thenReturn(Map.of("sp-soap", "nameid", "sp-other", "nameid2"));
        onGiveUp.getValue().run();

        verify(audit).record(argThat(abandonedFor("sp-soap")));
        verify(audit).record(argThat(abandonedFor("sp-other")));
        verify(index).clear(SID);
    }

    @Test
    void aSuccessfulSoapDeliveryIsNotRetried() {
        // No delivery HTTP double here; covered live by scripts/saml_slo_flow.py. This pins that when nothing
        // remains, the coordinator is told so (hasRemaining=false) and no give-up path is armed prematurely.
        when(index.lookup(SID)).thenReturn(Map.of());
        when(index.removeParticipants(SID, Set.of())).thenReturn(0);

        propagation.propagate(SID, USER);

        verify(retryCoordinator).reschedule(eq(SamlLogoutRetryDriver.RETRY_QUEUE), eq(SID), eq(USER), eq(false), any());
        verify(index, never()).clear(SID);
    }

    private static ArgumentMatcher<AuditRecord> frontChannelNoteFor(String entityId) {
        return r -> r != null && !r.success() && r.detail() != null
                && r.detail().contains(entityId) && r.detail().contains("front-channel");
    }

    private static ArgumentMatcher<AuditRecord> abandonedFor(String entityId) {
        return r -> r != null && !r.success() && r.detail() != null
                && r.detail().contains(entityId) && r.detail().contains("abandoned");
    }
}
