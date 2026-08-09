package com.example.sso.audit;

import java.time.Instant;
import java.util.List;
import java.util.Set;
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
    /**
     * Everything about one person — what they DID and what was done TO them.
     *
     * <p>Renamed from {@code recentForPrincipal}, which asked only the first question while its one caller
     * (the user-detail activity tab) meant the second. The two are different facts, and the audit row keeps
     * them in different fields: the actor is who performed the action, the subject is who it was performed on.
     * A screen titled "activity" for a person needs both, so it must pass both keys.
     */
    List<AuditEntry> recentAbout(UUID orgId, String username, UUID userId);

    /** The most recent events in a single category within one tenant (or the global scope), newest first. */
    List<AuditEntry> recentByCategory(UUID orgId, AuditCategory category);

    /**
     * The most recent events restricted to a SET of categories (the ALL view for a reader who may see only some
     * categories) within one tenant (or the global scope), newest first. An empty set returns no events.
     */
    List<AuditEntry> recentByCategories(UUID orgId, Set<AuditCategory> categories);

    /** Platform-wide count of completed sign-ins since a moment (analytics). */
    long signInsSince(Instant since);

    /** A tenant's daily sign-in trend (completed sign-ins vs failed attempts) since a moment. */
    List<AuditSignInDay> signInTrend(UUID orgId, Instant since);
}
