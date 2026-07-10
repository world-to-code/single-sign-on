package com.example.sso.saml.internal.api;

import com.example.sso.saml.internal.application.SamlBindingCodec;
import com.example.sso.saml.internal.application.SamlInboundLogoutService;
import com.example.sso.saml.internal.application.SamlInboundLogoutService.InboundLogout;
import com.example.sso.saml.internal.application.SamlLogoutChainCookie;
import com.example.sso.saml.internal.application.SamlLogoutChainService;
import com.example.sso.saml.internal.application.SamlLogoutChainService.ChainStep;
import com.example.sso.saml.internal.application.SamlSignatureValidator;
import com.example.sso.saml.internal.domain.SamlRelyingParty;
import com.example.sso.saml.internal.domain.SamlRelyingPartyRepository;
import com.example.sso.shared.error.BadRequestException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import org.opensaml.saml.saml2.core.LogoutRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * SAML 2.0 IdP Single Logout endpoint (binding adapter). Handles three cases:
 * <ul>
 *   <li>an SP-initiated {@code LogoutRequest} (verify SP signature, end the session, respond);</li>
 *   <li>a {@code LogoutResponse} returning from an SP during an IdP-initiated front-channel chain
 *       (advance to the next SP); and</li>
 *   <li>{@code /chain} — driving the next hop of that chain (emit a LogoutRequest to the next SP).</li>
 * </ul>
 */
@RequestMapping("/saml2/idp/slo")
@Controller
@RequiredArgsConstructor
public class SamlSloController {

    private static final String CSP_HEADER = "Content-Security-Policy";
    private static final String SIGNATURE_PARAM = "&Signature=";
    private static final String POST_LOGOUT_LANDING = "/";

    private final SamlBindingCodec codec;
    private final SamlRelyingPartyRepository relyingParties;
    private final SamlSignatureValidator signatureValidator;
    private final SamlInboundLogoutService inboundLogout;
    private final SamlLogoutChainService chainService;
    private final SamlLogoutChainCookie chainCookie;
    private final SecureRandom nonceRandom = new SecureRandom();

    @GetMapping
    public ResponseEntity<String> sloRedirect(
            @RequestParam(value = "SAMLRequest", required = false) String samlRequest,
            @RequestParam(value = "SAMLResponse", required = false) String samlResponse,
            @RequestParam(value = "RelayState", required = false) String relayState,
            @RequestParam(value = "SigAlg", required = false) String sigAlg,
            @RequestParam(value = "Signature", required = false) String signature,
            Authentication authentication, HttpServletRequest httpRequest) {
        if (samlResponse != null) {
            return advanceChain(relayState); // a chained SP's LogoutResponse came back — go to the next SP
        }
        LogoutRequest request = codec.decodeLogoutRedirect(require(samlRequest));
        SamlRelyingParty relyingParty = resolve(request);

        // ALWAYS verify the SP signature on an inbound LogoutRequest (unlike SSO, not gated on the per-RP
        // wantAuthnRequestsSigned flag): the signature prevents cross-site logout-CSRF, since /saml2/idp/slo
        // is permitAll and a Lax SESSION cookie IS sent on a top-level GET navigation.
        if (signature == null || sigAlg == null) {
            throw new BadRequestException("LogoutRequest must be signed (Redirect binding)");
        }
        String query = httpRequest.getQueryString();
        int idx = query.indexOf(SIGNATURE_PARAM);
        String signedContent = idx >= 0 ? query.substring(0, idx) : query;
        signatureValidator.verifyRedirect(signedContent.getBytes(StandardCharsets.US_ASCII), sigAlg,
                Base64.getDecoder().decode(signature), relyingParty);

        return render(inboundLogout.process(request, relyingParty, username(authentication, request),
                authentication, relayState, httpRequest));
    }

    @PostMapping
    public ResponseEntity<String> sloPost(
            @RequestParam(value = "SAMLRequest", required = false) String samlRequest,
            @RequestParam(value = "SAMLResponse", required = false) String samlResponse,
            @RequestParam(value = "RelayState", required = false) String relayState,
            Authentication authentication, HttpServletRequest httpRequest) {
        if (samlResponse != null) {
            return advanceChain(relayState);
        }
        LogoutRequest request = codec.decodeLogoutPost(require(samlRequest));
        SamlRelyingParty relyingParty = resolve(request);

        signatureValidator.verifyEmbedded(request, relyingParty); // always verify (see the Redirect handler)

        return render(inboundLogout.process(request, relyingParty, username(authentication, request),
                authentication, relayState, httpRequest));
    }

    /** Drives the next hop of a front-channel logout chain (emits a LogoutRequest to the next SP). */
    @GetMapping("/chain")
    public ResponseEntity<String> chain(@RequestParam("logout") String logoutId,
            HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        // The chain id rides RelayState to every SP, so it is not a secret. Require the browser-bound cookie
        // that logout set, so a participant SP that only learned the id cannot drive another user's chain.
        if (!chainCookie.matches(httpRequest, logoutId)) {
            chainCookie.clear(httpResponse);
            return redirectTo(POST_LOGOUT_LANDING);
        }
        String nonce = newNonce();
        return switch (chainService.next(logoutId, nonce)) {
            case ChainStep.Redirect redirect -> redirectTo(redirect.url());
            case ChainStep.PostForm form -> autoSubmit(form.html(), nonce);
            case ChainStep.Complete ignored -> {
                chainCookie.clear(httpResponse);
                yield redirectTo(POST_LOGOUT_LANDING);
            }
        };
    }

    /** A chained SP returned a LogoutResponse — continue the chain (RelayState carries the logout id). */
    private ResponseEntity<String> advanceChain(String logoutId) {
        return redirectTo(logoutId == null || logoutId.isBlank()
                ? POST_LOGOUT_LANDING : "/saml2/idp/slo/chain?logout=" + logoutId);
    }

    private String require(String samlRequest) {
        if (samlRequest == null) {
            throw new BadRequestException("SAMLRequest or SAMLResponse is required");
        }
        return samlRequest;
    }

    private SamlRelyingParty resolve(LogoutRequest request) {
        if (request.getIssuer() == null || request.getIssuer().getValue() == null || request.getID() == null) {
            throw new BadRequestException("LogoutRequest is missing Issuer or ID");
        }
        String spEntityId = request.getIssuer().getValue();
        return relyingParties.findByEntityId(spEntityId)
                .orElseThrow(() -> new BadRequestException("Unknown SP: " + spEntityId));
    }

    /** The subject to audit: the live session's principal, or the request NameID if the session is gone. */
    private String username(Authentication authentication, LogoutRequest request) {
        if (authentication != null) {
            return authentication.getName();
        }
        return request.getNameID() != null ? request.getNameID().getValue() : "unknown";
    }

    /** Posts the signed LogoutResponse back to the SP via an auto-submitting form (POST binding). */
    private ResponseEntity<String> render(InboundLogout result) {
        String nonce = newNonce();
        return autoSubmit(codec.postBindingHtml(result.sloUrl(), result.base64Response(),
                result.relayState(), nonce), nonce);
    }

    private ResponseEntity<String> autoSubmit(String html, String nonce) {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .header(CSP_HEADER, "default-src 'self'; script-src 'nonce-" + nonce + "'")
                .body(html);
    }

    private ResponseEntity<String> redirectTo(String location) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
    }

    private String newNonce() {
        byte[] bytes = new byte[16];
        nonceRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
