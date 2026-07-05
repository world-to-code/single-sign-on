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

    List<AuditEntry> recent();

    /** The most recent events recorded for a single principal (username), newest first. */
    List<AuditEntry> recentForPrincipal(String principal);

    /** The most recent events in a single category, newest first. */
    List<AuditEntry> recentByCategory(AuditCategory category);

    /** Platform-wide count of completed sign-ins since a moment (analytics). */
    long signInsSince(Instant since);

    /** A tenant's daily sign-in trend (completed sign-ins vs failed attempts) since a moment. */
    List<AuditSignInDay> signInTrend(UUID orgId, Instant since);
}
