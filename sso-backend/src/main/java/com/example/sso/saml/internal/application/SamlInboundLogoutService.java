package com.example.sso.saml.internal.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.saml.internal.domain.SamlRelyingParty;
import com.example.sso.shared.web.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.opensaml.saml.saml2.core.LogoutRequest;
import org.springframework.stereotype.Service;

/**
 * Handles an SP-initiated SAML {@code LogoutRequest}: terminates the IdP session (which fans out logout to
 * the user's OTHER OIDC/SAML participants via the session-termination listeners) and produces a signed
 * {@code LogoutResponse} for the initiating SP. Keeps HTTP/binding concerns in the controller.
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
                                 String relayState, HttpServletRequest httpRequest) {
        // Invalidating the current browser session fires SessionDestroyedEvent -> the OIDC + SAML(SOAP)
        // listeners log the user out of every OTHER participant. Front-channel SPs need the redirect chain.
        HttpSession session = httpRequest.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        audit.record(new AuditRecord(AuditType.SAML_SLO, username, true,
                "sp=" + rp.getEntityId() + " (sp-initiated)", ClientIp.of(httpRequest)));

        String base64Response = codec.encodeObject(messageBuilder.signedLogoutResponse(rp, request.getID()));
        return new InboundLogout(rp.getSingleLogoutUrl(), base64Response, relayState);
    }
}
