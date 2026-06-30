package com.example.sso.audit;

import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;

/**
 * Records base authentication outcomes (form login, OTT, client auth) to the audit log,
 * complementing the MFA/SAML/SCIM events recorded elsewhere.
 */
@Component
public class AuthenticationAuditListener {

    private final AuditService audit;

    public AuthenticationAuditListener(AuditService audit) {
        this.audit = audit;
    }

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        audit.record("AUTH_SUCCESS", event.getAuthentication().getName(), true);
    }

    @EventListener
    public void onFailure(AbstractAuthenticationFailureEvent event) {
        audit.record("AUTH_FAILURE", event.getAuthentication().getName(), false,
                event.getException().getMessage(), null);
    }
}
