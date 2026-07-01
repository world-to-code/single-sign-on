package com.example.sso.security.internal;

import com.example.sso.audit.AuditType;
import com.example.sso.audit.AuditService;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.security.authorization.event.AuthorizationDeniedEvent;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Zero-Trust observability: records every denied authorization decision (URL- and
 * method-level) so unauthorized access attempts are visible in the audit trail. Requires the
 * {@code AuthorizationEventPublisher} bean to be registered (see SecurityConfig).
 */
@Component
@RequiredArgsConstructor
public class AuthorizationAuditListener {

    private final AuditService audit;

    @EventListener
    public void onDenied(AuthorizationDeniedEvent<?> event) {
        Supplier<Authentication> supplier = event.getAuthentication();
        Authentication authentication = supplier == null ? null : supplier.get();
        String principal = authentication == null ? "anonymous" : authentication.getName();

        audit.record(AuditType.AUTHORIZATION_DENIED, principal, false);
    }
}
