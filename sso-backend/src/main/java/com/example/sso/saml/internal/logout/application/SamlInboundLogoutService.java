package com.example.sso.saml.internal.logout.application;

import com.example.sso.saml.internal.core.application.SamlBindingCodec;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.authpolicy.factor.Factors;
import com.example.sso.saml.internal.relyingparty.domain.SamlRelyingParty;
import com.example.sso.shared.web.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.opensaml.saml.saml2.core.LogoutRequest;
import org.opensaml.saml.saml2.core.SessionIndex;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

/**
 * Handles an SP-initiated SAML {@code LogoutRequest}: terminates the IdP session the request TARGETS (which
 * fans out logout to the user's OTHER OIDC/SAML participants via the session-termination listeners) and
 * produces a signed {@code LogoutResponse} for the initiating SP. Keeps HTTP/binding concerns in the
 * controller.
 *
 * <p>The request names its target with {@code SessionIndex} — the OP session id (`sid`) stamped into the
 * assertion at SSO. The ambient browser session is ended only when it is that session: a user signed in from
 * several browsers otherwise loses whichever session happened to carry the cookie on the SP's redirect. A
 * request with NO SessionIndex targets the subject's session at this IdP, so the ambient one is ended.
 *
 * <p>When nothing is terminated the SP is told so ({@code PartialLogout}) and the attempt is audited as a
 * failure — answering Success would have the SP record a completed logout while the IdP session, and its SSO
 * to every other relying party, survives.
 */
@Service
@RequiredArgsConstructor
public class SamlInboundLogoutService {

    private final SamlLogoutMessageBuilder messageBuilder;
    private final SamlBindingCodec codec;
    private final AuditService audit;

    /** The signed base64 LogoutResponse to return to the SP, plus where it is posted. */
    public record InboundLogout(String sloUrl, String base64Response, String relayState) {
    }

    public InboundLogout process(LogoutRequest request, SamlRelyingParty rp, String username,
                                 Authentication authentication, String relayState,
                                 HttpServletRequest httpRequest) {
        // Invalidating the session fires SessionDestroyedEvent -> the OIDC + SAML(SOAP) listeners log the user
        // out of every OTHER participant. Front-channel SPs need the redirect chain.
        HttpSession session = httpRequest.getSession(false);
        boolean terminated = session != null && targetsCurrentSession(request, authentication);
        if (terminated) {
            session.invalidate();
        }
        audit.record(new AuditRecord(AuditType.SAML_SLO, username, terminated,
                "sp=" + rp.getEntityId() + " (sp-initiated)" + (terminated ? "" : " no matching session"),
                ClientIp.of(httpRequest)));

        String base64Response = codec.encodeObject(
                messageBuilder.signedLogoutResponse(rp, request.getID(), terminated));
        return new InboundLogout(rp.getSingleLogoutUrl(), base64Response, relayState);
    }

    /** Whether the request's SessionIndex (if any) names the session id this browser is authenticated with. */
    private boolean targetsCurrentSession(LogoutRequest request, Authentication authentication) {
        List<SessionIndex> indexes = request.getSessionIndexes();
        if (indexes.isEmpty()) {
            return true; // no index: the request targets the subject's session at this IdP
        }
        String currentSid = sidOf(authentication);
        return currentSid != null && indexes.stream()
                .anyMatch(index -> currentSid.equals(index.getValue()));
    }

    /** The OP session id carried by the session's {@code SID_} marker authority, or null when unauthenticated. */
    private String sidOf(Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith(Factors.SID_PREFIX))
                .findFirst()
                .map(authority -> authority.substring(Factors.SID_PREFIX.length()))
                .orElse(null);
    }
}
