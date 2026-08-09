package com.example.sso.admin.internal.audit.api;

import com.example.sso.audit.export.AuditExportSettings;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Point the audit export at a collector.
 *
 * <p>The URL's real validation is the service's — https, a parseable authority, and a host outside the
 * internal network, each asked separately and again at use. Bean validation here only bounds the strings, so
 * an oversized body is refused before anything resolves DNS.
 */
public record AuditExportRequest(@NotBlank @Size(max = 2048) String endpointUrl,
                                 @NotBlank @Size(max = 512) String credential,
                                 boolean enabled) {

    public AuditExportSettings toSettings() {
        return new AuditExportSettings(endpointUrl, credential, enabled);
    }
}
