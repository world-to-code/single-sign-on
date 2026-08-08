package com.example.sso.security.internal;

import com.example.sso.admin.AdminRefusalTrail;
import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditType;
import com.example.sso.audit.AuditService;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.security.authorization.event.AuthorizationDeniedEvent;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Zero-Trust observability: records every denied authorization decision (URL- and method-level) so
 * unauthorized access attempts are visible in the audit trail. Requires the
 * {@code AuthorizationEventPublisher} bean to be registered (see SecurityConfig).
 *
 * <p>The row used to carry only the principal, which made every refusal read the same: "somebody was denied
 * something, somewhere". A misconfigured console and somebody walking every admin route produced identical
 * evidence. {@link DeniedAuthorizationDescriber} adds what was protected and which check refused it — and
 * is careful about what it will NOT say, since the protected object for method security carries credentials.
 */
@Component
@RequiredArgsConstructor
public class AuthorizationAuditListener {

    private final AuditService audit;
    private final AdminRefusalTrail refusalTrail;
    private final DeniedAuthorizationDescriber describer = new DeniedAuthorizationDescriber();

    /**
     * The expression names the rule; the trail names the clause inside it that objected.
     *
     * <p>A composed {@code @PreAuthorize} denies as one word — "has the permission AND may reach the target
     * AND may disable them" — so without this an operator learns which rule fired and not why. The trail is
     * consulted ONLY here, on an actual denial, which is what makes recording it unconditionally safe.
     */
    private String reasonFor(DeniedAuthorization denied) {
        return refusalTrail.lastRefusal()
                .map(refusal -> denied.failedCheck() == null ? refusal.name()
                        : denied.failedCheck() + " (" + refusal.name() + ")")
                .orElse(denied.failedCheck());
    }

    @EventListener
    public void onDenied(AuthorizationDeniedEvent<?> event) {
        Supplier<Authentication> supplier = event.getAuthentication();
        Authentication authentication = supplier == null ? null : supplier.get();
        String principal = authentication == null ? "anonymous" : authentication.getName();

        DeniedAuthorization denied = describer.describe(event.getObject(), event.getAuthorizationResult());
        audit.record(new AuditRecord(AuditType.AUTHORIZATION_DENIED, principal, false, denied.target(), null)
                .withReason(reasonFor(denied)));
    }
}
