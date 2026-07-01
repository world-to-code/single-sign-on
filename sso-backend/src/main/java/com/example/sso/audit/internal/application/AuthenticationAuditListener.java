package com.example.sso.audit.internal.application;

import com.example.sso.audit.AuditType;
import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;

/**
 * Records base authentication outcomes (form login, OTT, client auth) to the audit log,
 * complementing the MFA/SAML/SCIM events recorded elsewhere.
 */
@Component
@RequiredArgsConstructor
public class AuthenticationAuditListener {
    private final AuditService audit;

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        audit.record(AuditType.AUTH_SUCCESS, event.getAuthentication().getName(), true);
    }

    @EventListener
    public void onFailure(AbstractAuthenticationFailureEvent event) {
        audit.record(new AuditRecord(AuditType.AUTH_FAILURE, event.getAuthentication().getName(), false,
                event.getException().getMessage(), null));
    }
}
