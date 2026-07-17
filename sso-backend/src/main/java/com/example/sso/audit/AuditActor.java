package com.example.sso.audit;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The acting principal for an admin audit event — the authenticated name from the security context, or
 * {@code "unknown"} when there is none. The single home for "who performed this action", shared by the
 * {@code @Audited} interceptor and {@code AdminAuditLogger} so the attribution can never drift between them.
 */
public final class AuditActor {

    /** The current security-context principal's name, or {@code "unknown"} if unauthenticated. */
    public static String of() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? "unknown" : authentication.getName();
    }

    private AuditActor() {
    }
}
