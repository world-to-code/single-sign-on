package com.example.sso.saml;

import com.example.sso.audit.AuditService;
import com.example.sso.portal.AppAccess;
import com.example.sso.portal.AppAssignment;
import com.example.sso.portal.AppStepUpFilter;
import com.example.sso.portal.ApplicationService;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.user.AppUser;
import com.example.sso.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.opensaml.saml.saml2.core.AuthnRequest;
import org.opensaml.saml.saml2.core.Response;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * SAML 2.0 IdP Single Sign-On endpoint. Reaching it requires a fully MFA-authenticated session
 * (enforced by the app security chain). Supports both <b>SP-initiated</b> SSO (parse the SP's
 * {@code AuthnRequest} at {@code /saml2/idp/sso}) and <b>IdP-initiated</b> (unsolicited) SSO at
 * {@code /saml2/idp/sso/init?sp=...}. Either way it issues a signed {@code Response} and returns
 * an auto-submitting POST form to the SP's ACS.
 */
@RequestMapping("/saml2/idp/sso")
@Controller
public class SamlSsoController {

    private final SamlBindingCodec codec;
    private final SamlRelyingPartyRepository relyingParties;
    private final SamlResponseBuilder responseBuilder;
    private final SamlSignatureValidator signatureValidator;
    private final UserService users;
    private final ApplicationService applications;
    private final AuditService audit;

    public SamlSsoController(SamlBindingCodec codec,
                            SamlRelyingPartyRepository relyingParties,
                            SamlResponseBuilder responseBuilder,
                            SamlSignatureValidator signatureValidator,
                            UserService users,
                            ApplicationService applications,
                            AuditService audit) {
        this.codec = codec;
        this.relyingParties = relyingParties;
        this.responseBuilder = responseBuilder;
        this.signatureValidator = signatureValidator;
        this.users = users;
        this.applications = applications;
        this.audit = audit;
    }

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
                throw new BadRequestException("AuthnRequest must be signed (Redirect binding)");
            }
            // The signed octet string is the raw query up to (but excluding) "&Signature=".
            String query = httpRequest.getQueryString();
            int idx = query.indexOf("&Signature=");
            String signedContent = idx >= 0 ? query.substring(0, idx) : query;
            signatureValidator.verifyRedirect(signedContent.getBytes(StandardCharsets.US_ASCII), sigAlg,
                    Base64.getDecoder().decode(signature), relyingParty);
        }
        return respond(relyingParty, request.getID(), relayState, authentication, httpRequest);
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
        return respond(relyingParty, request.getID(), relayState, authentication, httpRequest);
    }

    /** IdP-initiated (unsolicited) SSO: the user launches the app from the IdP, no AuthnRequest. */
    @GetMapping("/init")
    public ResponseEntity<String> idpInitiated(@RequestParam("sp") String spEntityId,
                                               @RequestParam(value = "RelayState", required = false) String relayState,
                                               Authentication authentication, HttpServletRequest httpRequest) {
        SamlRelyingParty relyingParty = relyingParties.findByEntityId(spEntityId)
                .orElseThrow(() -> new BadRequestException("Unknown SP: " + spEntityId));
        if (!relyingParty.isAllowIdpInitiated()) {
            throw new BadRequestException("IdP-initiated SSO is not allowed for " + spEntityId);
        }
        return respond(relyingParty, null, relayState, authentication, httpRequest); // null InResponseTo = unsolicited
    }

    private SamlRelyingParty resolve(AuthnRequest request) {
        if (request.getIssuer() == null || request.getIssuer().getValue() == null || request.getID() == null) {
            throw new BadRequestException("AuthnRequest is missing Issuer or ID");
        }
        String spEntityId = request.getIssuer().getValue();
        return relyingParties.findByEntityId(spEntityId)
                .orElseThrow(() -> new BadRequestException("Unknown SP: " + spEntityId));
    }

    private ResponseEntity<String> respond(SamlRelyingParty relyingParty, String inResponseTo,
                                           String relayState, Authentication authentication, HttpServletRequest httpRequest) {
        AppUser user = users.findByUsername(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown user"));

        // Per-app step-up: this app may require extra factors beyond the base login.
        Set<String> granted = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).filter(a -> a.startsWith("FACTOR_")).collect(Collectors.toSet());
        AppAccess access = applications.appAccess(user, AppAssignment.AppType.SAML, relyingParty.getId().toString(), granted);
        if (!access.ready()) {
            if ("GET".equalsIgnoreCase(httpRequest.getMethod())) {
                HttpSession session = httpRequest.getSession(true);
                String query = httpRequest.getQueryString();
                session.setAttribute(AppStepUpFilter.RETURN, httpRequest.getRequestURI() + (query != null ? "?" + query : ""));
                session.setAttribute(AppStepUpFilter.APP_TYPE, "SAML");
                session.setAttribute(AppStepUpFilter.APP_ID, relyingParty.getId().toString());
                return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/stepup")).build();
            }
            audit.record("SAML_STEPUP_REQUIRED", user.getUsername(), false, "sp=" + relyingParty.getEntityId(), null);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).contentType(MediaType.TEXT_PLAIN)
                    .body("Additional authentication is required for this application.");
        }

        Response response = responseBuilder.issueResponse(
                relyingParty, inResponseTo, user.getEmail(), user.getDisplayName());
        String encoded = codec.encode(response);
        String flow = inResponseTo == null ? " (idp-initiated)" : "";
        audit.record("SAML_SSO_ISSUED", user.getUsername(), true, "sp=" + relyingParty.getEntityId() + flow, null);

        String html = codec.postBindingHtml(relyingParty.getAcsUrl(), encoded, relayState);
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
    }
}
