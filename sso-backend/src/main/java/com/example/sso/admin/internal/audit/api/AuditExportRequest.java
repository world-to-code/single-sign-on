package com.example.sso.admin.internal.audit.api;

import com.example.sso.audit.export.AuditExportSettings;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Point the audit export at a collector.
 *
 * <p>The credential is deliberately NOT {@code @NotBlank}: blank means "keep the stored one", so that
 * editing the URL — or switching the export off — never requires re-entering a secret the console was never
 * shown. The "required the first time" rule lives in the service, which is the only place that knows whether
 * there is a stored one to keep.
 *
 * <p>The URL's real validation is the service's — https, a parseable authority, and a host outside the
 * internal network, each asked separately and again at use. Bean validation here only bounds the strings, so
 * an oversized body is refused before anything resolves DNS.
 */
public record AuditExportRequest(@NotBlank @Size(max = 2048) String endpointUrl,
                                 @Size(max = 512) String credential,
                                 boolean enabled,
                                 boolean includePii) {

    public AuditExportSettings toSettings() {
        return new AuditExportSettings(endpointUrl, credential, enabled, includePii);
    }
}
