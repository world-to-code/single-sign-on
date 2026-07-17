package com.example.sso.saml.internal.logout.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.saml.internal.core.application.SamlBindingCodec;
import com.example.sso.saml.internal.core.application.SamlRedirectEncoder;
import com.example.sso.saml.internal.relyingparty.domain.SamlRelyingParty;
import com.example.sso.saml.internal.relyingparty.domain.SamlRelyingPartyRepository;

import com.example.sso.saml.relyingparty.SloBinding;
import com.example.sso.saml.internal.logout.application.SamlLogoutChainService.ChainStep;
import com.example.sso.saml.internal.logout.application.SamlLogoutChainStore.Hop;
import com.example.sso.saml.internal.logout.application.SamlLogoutChainStore.Responder;
import com.example.sso.tenancy.OrgContext;
import org.opensaml.saml.saml2.core.LogoutResponse;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The chain service must pick the right browser step per SP binding and complete when the chain is empty. */
@ExtendWith(MockitoExtension.class)
class SamlLogoutChainServiceTest {

    @Mock
    SamlLogoutChainStore chainStore;
    @Mock
    SamlRelyingPartyRepository relyingParties;
    @Mock
    SamlLogoutMessageBuilder messageBuilder;
    @Mock
    SamlRedirectEncoder redirectEncoder;
    @Mock
    SamlBindingCodec codec;
    @Mock
    OrgContext orgContext;
    @Mock
    AuditService audit;
    @InjectMocks
    SamlLogoutChainService service;

    @BeforeEach
    void setUp() {
        // resolution runs in platform context; run the supplier inline. RPs default to org null (global),
        // so callInOrg is not exercised here.
        lenient().when(orgContext.callAsPlatform(any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
    }

    private SamlRelyingParty rp(SloBinding binding) {
        SamlRelyingParty rp = mock(SamlRelyingParty.class);
        when(rp.getSingleLogoutUrl()).thenReturn("https://sp.example/slo");
        when(rp.sloBinding()).thenReturn(binding);
        return rp;
    }

    @Test
    void redirectBindingHopProducesARedirectStep() {
        SamlRelyingParty rp = rp(SloBinding.REDIRECT);
        when(chainStore.next("L")).thenReturn(Optional.of(new Hop("sp", "user@x", "sid-1")));
        when(relyingParties.findByEntityId("sp")).thenReturn(Optional.of(rp));
        when(messageBuilder.unsignedLogoutRequestXml(any(), eq("user@x"), eq("sid-1")))
                .thenReturn("<xml/>");
        when(redirectEncoder.encodeRequest(any(), any(), eq("L"), any()))
                .thenReturn("https://sp.example/slo?SAMLRequest=..&Signature=..");

        ChainStep step = service.next("L", "nonce");

        assertThat(step).isInstanceOf(ChainStep.Redirect.class);
    }

    @Test
    void postBindingHopProducesAPostFormStep() {
        SamlRelyingParty rp = rp(SloBinding.POST);
        when(chainStore.next("L")).thenReturn(Optional.of(new Hop("sp", "user@x", "sid-1")));
        when(relyingParties.findByEntityId("sp")).thenReturn(Optional.of(rp));
        when(messageBuilder.signedLogoutRequestXml(any(), eq("user@x"), eq("sid-1")))
                .thenReturn("<xml/>");
        when(codec.postRequestHtml(any(), any(), eq("L"), eq("nonce")))
                .thenReturn("<html/>");

        ChainStep step = service.next("L", "nonce");

        assertThat(step).isInstanceOf(ChainStep.PostForm.class);
    }

    @Test
    void orgScopedHopIsResolvedAndBuiltInsideTheRelyingPartysTenant() {
        UUID org = UUID.randomUUID();
        SamlRelyingParty rp = rp(SloBinding.POST);
        when(rp.getOrgId()).thenReturn(org);
        when(chainStore.next("L")).thenReturn(Optional.of(new Hop("sp", "user@x", "sid-1")));
        when(relyingParties.findByEntityId("sp")).thenReturn(Optional.of(rp));
        when(orgContext.callInOrg(eq(org), any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        when(messageBuilder.signedLogoutRequestXml(any(), eq("user@x"), eq("sid-1"))).thenReturn("<xml/>");
        when(codec.postRequestHtml(any(), any(), eq("L"), eq("nonce"))).thenReturn("<html/>");

        ChainStep step = service.next("L", "nonce");

        assertThat(step).isInstanceOf(ChainStep.PostForm.class);
        verify(orgContext).callInOrg(eq(org), any()); // signed under the tenant's SAML key, not the global one
    }

    @Test
    void emittingAHopAuditsTheFrontChannelLogoutAsDelivered() {
        SamlRelyingParty rp = rp(SloBinding.REDIRECT);
        when(chainStore.next("L")).thenReturn(Optional.of(new Hop("sp", "user@x", "sid-1")));
        when(relyingParties.findByEntityId("sp")).thenReturn(Optional.of(rp));
        when(messageBuilder.unsignedLogoutRequestXml(any(), eq("user@x"), eq("sid-1"))).thenReturn("<xml/>");
        when(redirectEncoder.encodeRequest(any(), any(), eq("L"), any())).thenReturn("https://sp.example/slo?..");

        service.next("L", "nonce");

        // A front-channel SP the chain logs out must leave a positive trail, so it is not represented ONLY by
        // the back-channel path's "not reachable" note.
        verify(audit).record(argThat(r -> r != null && r.success()
                && r.detail() != null && r.detail().contains("sp") && r.detail().contains("front-channel")));
    }

    @Test
    void exhaustedChainCompletesAndClears() {
        when(chainStore.next("L")).thenReturn(Optional.empty());

        ChainStep step = service.next("L", "nonce");

        assertThat(step).isInstanceOf(ChainStep.Complete.class);
        verify(chainStore).clear("L");
        verifyNoInteractions(audit); // nothing emitted → nothing to audit
    }

    @Test
    void exhaustedChainWithAnInitiatorAnswersItWithItsLogoutResponse() {
        // An SP-initiated logout: once the OTHER front-channel SPs drain, the originator gets its LogoutResponse
        // as the final hop (InResponseTo its request id), signed and posted back to its SLO URL.
        SamlRelyingParty initiator = mock(SamlRelyingParty.class);
        when(initiator.getSingleLogoutUrl()).thenReturn("https://initiator.example/slo");
        when(chainStore.next("L")).thenReturn(Optional.empty());
        when(chainStore.responder("L"))
                .thenReturn(Optional.of(new Responder("https://initiator.example", "req-9", "relay")));
        when(relyingParties.findByEntityId("https://initiator.example")).thenReturn(Optional.of(initiator));
        when(messageBuilder.signedLogoutResponse(any(), eq("req-9"), eq(true)))
                .thenReturn(mock(LogoutResponse.class));
        when(codec.encodeObject(any())).thenReturn("b64");

        ChainStep step = service.next("L", "nonce");

        assertThat(step).isInstanceOf(ChainStep.RespondToInitiator.class);
        ChainStep.RespondToInitiator r = (ChainStep.RespondToInitiator) step;
        assertThat(r.sloUrl()).isEqualTo("https://initiator.example/slo");
        assertThat(r.base64Response()).isEqualTo("b64");
        assertThat(r.relayState()).isEqualTo("relay");
        // InResponseTo = the initiator's request id, and a Success (terminated=true) status.
        verify(messageBuilder).signedLogoutResponse(any(), eq("req-9"), eq(true));
        verify(chainStore).clear("L");
    }

    @Test
    void theInitiatorResponseIsSignedInTheInitiatorRelyingPartysTenant() {
        UUID org = UUID.randomUUID();
        SamlRelyingParty initiator = mock(SamlRelyingParty.class);
        when(initiator.getSingleLogoutUrl()).thenReturn("https://initiator.example/slo");
        when(initiator.getOrgId()).thenReturn(org);
        when(chainStore.next("L")).thenReturn(Optional.empty());
        when(chainStore.responder("L"))
                .thenReturn(Optional.of(new Responder("https://initiator.example", "req-9", null)));
        when(relyingParties.findByEntityId("https://initiator.example")).thenReturn(Optional.of(initiator));
        when(orgContext.callInOrg(eq(org), any())).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        when(messageBuilder.signedLogoutResponse(any(), eq("req-9"), eq(true))).thenReturn(mock(LogoutResponse.class));
        when(codec.encodeObject(any())).thenReturn("b64");

        service.next("L", "nonce");

        verify(orgContext).callInOrg(eq(org), any()); // signed with the initiator tenant's SAML key, not the global one
    }

    @Test
    void aVanishedInitiatorRelyingPartyCompletesInsteadOfFailing() {
        // The session is already terminated; if the initiator RP was deleted mid-flow, just complete (no crash).
        when(chainStore.next("L")).thenReturn(Optional.empty());
        when(chainStore.responder("L")).thenReturn(Optional.of(new Responder("gone", "req-9", null)));
        when(relyingParties.findByEntityId("gone")).thenReturn(Optional.empty());

        ChainStep step = service.next("L", "nonce");

        assertThat(step).isInstanceOf(ChainStep.Complete.class);
        verify(chainStore).clear("L");
    }

    @Test
    void anInitiatorRelyingPartyWithNoSloUrlCompletes() {
        SamlRelyingParty initiator = mock(SamlRelyingParty.class);
        when(initiator.getSingleLogoutUrl()).thenReturn("");
        when(chainStore.next("L")).thenReturn(Optional.empty());
        when(chainStore.responder("L")).thenReturn(Optional.of(new Responder("initiator", "req-9", null)));
        when(relyingParties.findByEntityId("initiator")).thenReturn(Optional.of(initiator));

        assertThat(service.next("L", "nonce")).isInstanceOf(ChainStep.Complete.class);
    }
}
