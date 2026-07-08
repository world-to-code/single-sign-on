package com.example.sso.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Audit module's public contract: records security-relevant events and exposes recent events as the
 * public {@link AuditEntry} projection. The implementation and the backing entity stay module-internal.
 */
public interface AuditService {

    void record(AuditRecord record);

    void record(AuditType type, String principal, boolean success);

    /**
     * The most recent events in one tenant ({@code orgId}) or the platform/global scope ({@code orgId} null),
     * newest first. Scoped in the query — a tenant tier never sees another tenant's events, and the platform
     * tier sees only global (org-less) events, never all tenants merged.
     */
    List<AuditEntry> recent(UUID orgId);

    /** The most recent events for a single principal within one tenant (or the global scope), newest first. */
    List<AuditEntry> recentForPrincipal(UUID orgId, String principal);

    /** The most recent events in a single category within one tenant (or the global scope), newest first. */
    List<AuditEntry> recentByCategory(UUID orgId, AuditCategory category);

    /** Platform-wide count of completed sign-ins since a moment (analytics). */
    long signInsSince(Instant since);

    /** A tenant's daily sign-in trend (completed sign-ins vs failed attempts) since a moment. */
    List<AuditSignInDay> signInTrend(UUID orgId, Instant since);
}
