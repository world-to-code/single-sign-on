package com.example.sso.audit.export;

import java.util.Optional;

/**
 * The collector's configuration: one destination for the whole deployment.
 *
 * <p>Not per-tenant, unlike the SMTP and SMS settings. A tenant choosing where its own security history goes
 * is the one redirection that must not be possible, and the exporter reads ACROSS tenants anyway, so
 * per-tenant destinations would have no coherent meaning for it.
 */
public interface AuditExportSettingsService {

    /**
     * Stores the collector, replacing any existing one. Refuses a destination that is not https, or whose
     * host resolves into the internal network — the payload is every tenant's security history and the
     * collector credential travels with it.
     */
    void save(AuditExportSettings settings);

    /** The configuration as stored, for the console to render; the credential is never included. */
    Optional<AuditExportView> current();

    /**
     * The collector to send to right now, or empty when none is configured, it is switched off, or it no
     * longer passes validation. Re-checked HERE rather than trusted from the write path, because a row can be
     * edited afterwards and a host can be repointed after it was admitted.
     */
    Optional<AuditExportTarget> target();
}
