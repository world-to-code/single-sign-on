package com.example.sso.saml.internal.api;

import com.example.sso.saml.internal.application.SamlBindingCodec;
import com.example.sso.saml.internal.application.SamlInboundLogoutService;
import com.example.sso.saml.internal.application.SamlInboundLogoutService.InboundLogout;
import com.example.sso.saml.internal.application.SamlSignatureValidator;
import com.example.sso.saml.internal.domain.SamlRelyingParty;
import com.example.sso.saml.internal.domain.SamlRelyingPartyRepository;
import com.example.sso.shared.error.BadRequestException;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import org.opensaml.saml.saml2.core.LogoutRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * SAML 2.0 IdP Single Logout endpoint (binding adapter): accepts an SP-initiated {@code LogoutRequest}
 * over the HTTP-Redirect/POST bindings, verifies the SP signature, then delegates to
 * {@link SamlInboundLogoutService} (which terminates the session and fans out logout) and posts a signed
 * {@code LogoutResponse} back to the SP.
 */
@RequestMapping("/saml2/idp/slo")
@Controller
@RequiredArgsConstructor
public class SamlSloController {

    private static final String CSP_HEADER = "Content-Security-Policy";
    private static final String SIGNATURE_PARAM = "&Signature=";

    private final SamlBindingCodec codec;
    private final SamlRelyingPartyRepository relyingParties;
    private final SamlSignatureValidator signatureValidator;
    private final SamlInboundLogoutService inboundLogout;
    private final SecureRandom nonceRandom = new SecureRandom();

    @GetMapping
    public ResponseEntity<String> sloRedirect(@RequestParam("SAMLRequest") String samlRequest,
                                              @RequestParam(value = "RelayState", required = false) String relayState,
                                              @RequestParam(value = "SigAlg", required = false) String sigAlg,
                                              @RequestParam(value = "Signature", required = false) String signature,
                                              Authentication authentication, HttpServletRequest httpRequest) {
        LogoutRequest request = codec.decodeLogoutRedirect(samlRequest);
        SamlRelyingParty relyingParty = resolve(request);

        // ALWAYS verify the SP signature on an inbound LogoutRequest (unlike SSO, this is not gated on the
        // per-RP wantAuthnRequestsSigned flag): the signature is what prevents cross-site logout-CSRF, since
        // /saml2/idp/slo is permitAll and a Lax SESSION cookie IS sent on a top-level GET navigation.
        if (signature == null || sigAlg == null) {
            throw new BadRequestException("LogoutRequest must be signed (Redirect binding)");
        }
        String query = httpRequest.getQueryString();
        int idx = query.indexOf(SIGNATURE_PARAM);
        String signedContent = idx >= 0 ? query.substring(0, idx) : query;
        signatureValidator.verifyRedirect(signedContent.getBytes(StandardCharsets.US_ASCII), sigAlg,
                Base64.getDecoder().decode(signature), relyingParty);

        return render(inboundLogout.process(request, relyingParty, username(authentication, request),
                relayState, httpRequest));
    }

    @PostMapping
    public ResponseEntity<String> sloPost(@RequestParam("SAMLRequest") String samlRequest,
                                         @RequestParam(value = "RelayState", required = false) String relayState,
                                         Authentication authentication, HttpServletRequest httpRequest) {
        LogoutRequest request = codec.decodeLogoutPost(samlRequest);
        SamlRelyingParty relyingParty = resolve(request);

        // Always verify (see the Redirect handler) — the SP signature authenticates the LogoutRequest.
        signatureValidator.verifyEmbedded(request, relyingParty);

        return render(inboundLogout.process(request, relyingParty, username(authentication, request),
                relayState, httpRequest));
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
        String html = codec.postBindingHtml(result.sloUrl(), result.base64Response(), result.relayState(), nonce);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .header(CSP_HEADER, "default-src 'self'; script-src 'nonce-" + nonce + "'")
                .body(html);
    }

    private String newNonce() {
        byte[] bytes = new byte[16];
        nonceRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
