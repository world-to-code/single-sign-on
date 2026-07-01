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
}
