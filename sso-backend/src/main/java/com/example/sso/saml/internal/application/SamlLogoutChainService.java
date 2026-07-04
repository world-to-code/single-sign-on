package com.example.sso.saml.internal.application;

import com.example.sso.saml.SloBinding;
import com.example.sso.saml.internal.application.SamlLogoutChainStore.Hop;
import com.example.sso.saml.internal.domain.SamlRelyingParty;
import com.example.sso.saml.internal.domain.SamlRelyingPartyRepository;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
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
    }

    public ChainStep next(String logoutId, String scriptNonce) {
        Optional<Hop> hop = chainStore.next(logoutId);
        if (hop.isEmpty()) {
            chainStore.clear(logoutId);
            return new ChainStep.Complete();
        }
        Hop h = hop.get();
        SamlRelyingParty rp = relyingParties.findByEntityId(h.entityId()).orElse(null);
        if (rp == null || !StringUtils.hasText(rp.getSingleLogoutUrl())) {
            return next(logoutId, scriptNonce); // SP vanished / not configured — skip to the next hop
        }
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
