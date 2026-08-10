package com.example.sso.audit.export;

import java.time.Instant;

/**
 * The collector as the console shows it. Carries no credential — a configuration screen never needs to read
 * a secret back, and a view that could would be one more place it can leak from.
 */
public record AuditExportView(String endpointUrl, boolean enabled, boolean includePii, Instant updatedAt,
                              String updatedBy) {
}
