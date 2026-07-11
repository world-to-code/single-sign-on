package com.example.sso.saml.internal.sso.api;

import com.example.sso.saml.internal.core.application.SamlBindingCodec;
import com.example.sso.saml.internal.sso.application.SamlSsoOutcome;
import com.example.sso.saml.internal.sso.application.SamlSsoService;
import com.example.sso.saml.internal.credential.application.SamlSignatureValidator;
import com.example.sso.saml.internal.relyingparty.domain.SamlRelyingParty;
import com.example.sso.saml.internal.relyingparty.domain.SamlRelyingPartyRepository;
import com.example.sso.shared.error.BadRequestException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import org.opensaml.saml.saml2.core.AuthnRequest;
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
 * SAML 2.0 IdP Single Sign-On endpoint (binding adapter). Reaching it requires a fully MFA-authenticated
 * session (enforced by the app security chain). Handles the SP-initiated Redirect/POST bindings and the
 * IdP-initiated launch, verifies the request signature, then delegates the SSO decision to
 * {@link SamlSsoService} and renders its {@link SamlSsoOutcome}.
 */
@RequestMapping("/saml2/idp/sso")
@Controller
@RequiredArgsConstructor
public class SamlSsoController {

    private static final String CSP_HEADER = "Content-Security-Policy";
    private static final String STEPUP_PATH = "/stepup";
    private static final String SIGNATURE_PARAM = "&Signature=";

    private final SamlBindingCodec codec;
    private final SamlRelyingPartyRepository relyingParties;
    private final SamlSignatureValidator signatureValidator;
    private final SamlSsoService sso;
    private final SecureRandom nonceRandom = new SecureRandom();

    @GetMapping
    public ResponseEntity<String> ssoRedirect(@RequestParam("SAMLRequest") String samlRequest,
                                              @RequestParam(value = "RelayState", required = false) String relayState,
                                              @RequestParam(value = "SigAlg", required = false) String sigAlg,
                                              @RequestParam(value = "Signature", required = false) String signature,
                                              Authentication authentication, HttpServletRequest httpRequest) {
        AuthnRequest request = codec.decodeRedirect(samlRequest);
        SamlRelyingParty relyingParty = resolve(request);

        if (relyingParty.isWantAuthnRequestsSigned()) {
            if (signature == null || sigAlg == null) {
                throw BadRequestException.of("saml.authnRequest.unsigned");
            }

            // The signed octet string is the raw query up to (but excluding) "&Signature=".
            String query = httpRequest.getQueryString();
            int idx = query.indexOf(SIGNATURE_PARAM);
            String signedContent = idx >= 0 ? query.substring(0, idx) : query;
            signatureValidator.verifyRedirect(signedContent.getBytes(StandardCharsets.US_ASCII), sigAlg,
                    Base64.getDecoder().decode(signature), relyingParty);
        }

        return render(sso.process(relyingParty, request.getID(), relayState, authentication, httpRequest));
    }

    @PostMapping
    public ResponseEntity<String> ssoPost(@RequestParam("SAMLRequest") String samlRequest,
                                         @RequestParam(value = "RelayState", required = false) String relayState,
                                         Authentication authentication, HttpServletRequest httpRequest) {
        AuthnRequest request = codec.decodePost(samlRequest);
        SamlRelyingParty relyingParty = resolve(request);

        if (relyingParty.isWantAuthnRequestsSigned()) {
            signatureValidator.verifyEmbedded(request, relyingParty);
        }

        return render(sso.process(relyingParty, request.getID(), relayState, authentication, httpRequest));
    }

    /** IdP-initiated (unsolicited) SSO: the user launches the app from the IdP, no AuthnRequest. */
    @GetMapping("/init")
    public ResponseEntity<String> idpInitiated(@RequestParam("sp") String spEntityId,
                                               @RequestParam(value = "RelayState", required = false) String relayState,
                                               Authentication authentication, HttpServletRequest httpRequest) {
        SamlRelyingParty relyingParty = relyingParties.findByEntityId(spEntityId)
                .orElseThrow(() -> BadRequestException.of("saml.sp.unknown", spEntityId));

        if (!relyingParty.isAllowIdpInitiated()) {
            throw BadRequestException.of("saml.sso.idpInitiatedNotAllowed", spEntityId);
        }

        return render(sso.process(relyingParty, null, relayState, authentication, httpRequest)); // null InResponseTo = unsolicited
    }

    private SamlRelyingParty resolve(AuthnRequest request) {
        if (request.getIssuer() == null || request.getIssuer().getValue() == null || request.getID() == null) {
            throw BadRequestException.of("saml.authnRequest.missingIssuerOrId");
        }

        String spEntityId = request.getIssuer().getValue();
        return relyingParties.findByEntityId(spEntityId)
                .orElseThrow(() -> BadRequestException.of("saml.sp.unknown", spEntityId));
    }

    private ResponseEntity<String> render(SamlSsoOutcome outcome) {
        return switch (outcome) {
            case SamlSsoOutcome.Issued issued -> renderPostForm(issued);
            case SamlSsoOutcome.StepUpRedirect ignored ->
                    ResponseEntity.status(HttpStatus.FOUND).location(URI.create(STEPUP_PATH)).build();
            case SamlSsoOutcome.StepUpForbidden ignored ->
                    ResponseEntity.status(HttpStatus.FORBIDDEN).contentType(MediaType.TEXT_PLAIN)
                            .body("Additional authentication is required for this application.");
        };
    }

    private ResponseEntity<String> renderPostForm(SamlSsoOutcome.Issued issued) {
        // The auto-submit page needs an inline script; serve it under a per-response CSP that allows ONLY
        // that nonce'd script (overriding the app's strict default-src 'self', which blocks inline JS).
        // Spring Security's CSP writer skips when the header is already set, so this per-response CSP wins.
        String nonce = newNonce();
        String html = codec.postBindingHtml(issued.acsUrl(), issued.samlResponse(), issued.relayState(), nonce);
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
