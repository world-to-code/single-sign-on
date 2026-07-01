package com.example.sso.audit;

import java.util.List;

/**
 * Audit module's public contract: records security-relevant events and exposes recent events as the
 * public {@link AuditEntry} projection. The implementation and the backing entity stay module-internal.
 */
public interface AuditService {

    void record(AuditRecord record);

    void record(String type, String principal, boolean success);

    List<AuditEntry> recent();

    /** The most recent events recorded for a single principal (username), newest first. */
    List<AuditEntry> recentForPrincipal(String principal);

    /** The most recent events in a single category, newest first. */
    List<AuditEntry> recentByCategory(AuditCategory category);
}
