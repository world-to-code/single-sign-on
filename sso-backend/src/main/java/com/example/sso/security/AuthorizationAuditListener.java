package com.example.sso.security;

import com.example.sso.audit.AuditService;
import org.springframework.context.event.EventListener;
import org.springframework.security.authorization.event.AuthorizationDeniedEvent;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Zero-Trust observability: records every denied authorization decision (URL- and
 * method-level) so unauthorized access attempts are visible in the audit trail. Requires the
 * {@code AuthorizationEventPublisher} bean to be registered (see SecurityConfig).
 */
@Component
public class AuthorizationAuditListener {

    private final AuditService audit;

    public AuthorizationAuditListener(AuditService audit) {
        this.audit = audit;
    }

    @EventListener
    public void onDenied(AuthorizationDeniedEvent<?> event) {
        Supplier<Authentication> supplier = event.getAuthentication();
        Authentication authentication = supplier == null ? null : supplier.get();
        String principal = authentication == null ? "anonymous" : authentication.getName();
        audit.record("AUTHORIZATION_DENIED", principal, false);
    }
}
