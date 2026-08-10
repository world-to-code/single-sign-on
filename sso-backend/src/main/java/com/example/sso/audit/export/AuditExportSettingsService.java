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
     * Stores the collector, replacing any existing one. A blank credential on an UPDATE keeps the stored one —
     * it is write-only and never read back, so editing the URL must not wipe it. Refuses a destination that is
     * not https, or whose host resolves into the internal network — the payload is every tenant's security
     * history and the collector credential travels with it.
     */
    void save(AuditExportSettings settings);

    /** The configuration as stored, for the console to render; the credential is never included. */
    Optional<AuditExportView> current();

    /**
     * Removes the collector, stopping the export.
     *
     * <p>Exists so switching an export off never depends on holding the credential that authenticates it: an
     * operator who has lost the bearer must still be able to stop shipping to a destination they distrust.
     */
    void delete();

    /**
     * The collector to send to right now, or empty when none is configured, it is switched off, or it no
     * longer passes validation. Re-checked HERE rather than trusted from the write path, because a row can be
     * edited afterwards and a host can be repointed after it was admitted.
     */
    Optional<AuditExportTarget> target();
}
