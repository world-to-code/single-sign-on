package com.example.sso.saml.internal.logout.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.saml.internal.core.application.SamlBindingCodec;
import com.example.sso.saml.internal.core.application.SamlRedirectEncoder;

import com.example.sso.saml.relyingparty.SloBinding;
import com.example.sso.saml.internal.logout.application.SamlLogoutChainStore.Hop;
import com.example.sso.saml.internal.logout.application.SamlLogoutChainStore.Responder;
import com.example.sso.saml.internal.relyingparty.domain.SamlRelyingParty;
import com.example.sso.saml.internal.relyingparty.domain.SamlRelyingPartyRepository;
import com.example.sso.tenancy.OrgContext;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.opensaml.saml.saml2.core.LogoutResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Drives one hop of a front-channel SLO redirect chain: pops the next SP and produces the browser step to
 * emit its LogoutRequest over the SP's binding (a 302 for Redirect, an auto-submit form for POST), carrying
 * the {@code logoutId} as RelayState so the SP's LogoutResponse returns to advance the chain. When the chain
 * is exhausted it signals completion (the caller redirects the browser to the post-logout landing).
 */
@Service
@RequiredArgsConstructor
public class SamlLogoutChainService {

    private final SamlLogoutChainStore chainStore;
    private final SamlRelyingPartyRepository relyingParties;
    private final SamlLogoutMessageBuilder messageBuilder;
    private final SamlRedirectEncoder redirectEncoder;
    private final SamlBindingCodec codec;
    private final OrgContext orgContext;
    private final AuditService audit;

    /** The browser step for this hop. */
    public sealed interface ChainStep {
        /** Redirect (302) the browser to this URL (a signed Redirect-binding LogoutRequest). */
        record Redirect(String url) implements ChainStep {
        }

        /** Serve this auto-submit HTML (a POST-binding LogoutRequest). */
        record PostForm(String html) implements ChainStep {
        }

        /** The chain is finished — nothing more to send. */
        record Complete() implements ChainStep {
        }

        /** The chain drained on an SP-initiated logout — auto-submit this LogoutResponse back to the initiator. */
        record RespondToInitiator(String sloUrl, String base64Response, String relayState) implements ChainStep {
        }
    }

    public ChainStep next(String logoutId, String scriptNonce) {
        Optional<Hop> hop = chainStore.next(logoutId);
        if (hop.isEmpty()) {
            ChainStep end = finish(logoutId); // answer the SP-initiated originator, if any, else just complete
            chainStore.clear(logoutId);
            return end;
        }
        Hop h = hop.get();
        // The chain runs on the anonymous, post-logout /chain request (no OrgContext bound), so resolve the RP
        // in platform context — an org-scoped RP would otherwise be RLS-invisible and its hop silently skipped.
        SamlRelyingParty rp = orgContext.callAsPlatform(() -> relyingParties.findByEntityId(h.entityId()).orElse(null));
        if (rp == null || !StringUtils.hasText(rp.getSingleLogoutUrl())) {
            return next(logoutId, scriptNonce); // SP vanished / not configured — skip to the next hop
        }
        // Build the hop bound to the RP's org so its LogoutRequest is signed with that tenant's SAML key; a
        // global RP (org null) builds in the ambient context.
        UUID org = rp.getOrgId();
        ChainStep step = org == null
                ? buildStep(rp, h, logoutId, scriptNonce)
                : orgContext.callInOrg(org, () -> buildStep(rp, h, logoutId, scriptNonce));
        // The interactive chain is what actually logs a front-channel SP out. Record it, so a front-channel SP
        // that WAS logged out has a positive trail — not only the back-channel path's "not reachable" note.
        audit.record(new AuditRecord(AuditType.SAML_SLO, h.nameId(), true,
                "sp=" + h.entityId() + " front-channel logout", null));
        return step;
    }

    /** When the chain drains: answer the SP-initiated originator with its LogoutResponse, or just complete. */
    private ChainStep finish(String logoutId) {
        return chainStore.responder(logoutId).map(this::respondToInitiator).orElseGet(ChainStep.Complete::new);
    }

    private ChainStep respondToInitiator(Responder responder) {
        // Resolve + sign in the initiator RP's org so the LogoutResponse carries that tenant's SAML signature
        // (the /chain request is anonymous with no OrgContext bound; a global RP signs in the ambient context).
        SamlRelyingParty rp = orgContext.callAsPlatform(
                () -> relyingParties.findByEntityId(responder.entityId()).orElse(null));
        if (rp == null || !StringUtils.hasText(rp.getSingleLogoutUrl())) {
            return new ChainStep.Complete(); // initiator vanished/unconfigured — the session is already gone
        }
        UUID org = rp.getOrgId();
        LogoutResponse response = org == null
                ? messageBuilder.signedLogoutResponse(rp, responder.requestId(), true)
                : orgContext.callInOrg(org, () -> messageBuilder.signedLogoutResponse(rp, responder.requestId(), true));
        return new ChainStep.RespondToInitiator(rp.getSingleLogoutUrl(), codec.encodeObject(response),
                responder.relayState());
    }

    private ChainStep buildStep(SamlRelyingParty rp, Hop h, String logoutId, String scriptNonce) {
        if (rp.sloBinding() == SloBinding.POST) {
            String signed = messageBuilder.signedLogoutRequestXml(rp, h.nameId(), h.sid());
            String base64 = Base64.getEncoder().encodeToString(signed.getBytes(StandardCharsets.UTF_8));
            return new ChainStep.PostForm(
                    codec.postRequestHtml(rp.getSingleLogoutUrl(), base64, logoutId, scriptNonce));
        }
        String unsigned = messageBuilder.unsignedLogoutRequestXml(rp, h.nameId(), h.sid());
        return new ChainStep.Redirect(
                redirectEncoder.encodeRequest(rp.getSingleLogoutUrl(), unsigned, logoutId, rp.getSignatureAlgorithm()));
    }
}
