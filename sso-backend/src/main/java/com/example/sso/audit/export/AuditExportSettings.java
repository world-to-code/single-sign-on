package com.example.sso.audit.export;

/**
 * Where to ship the audit trail, as an administrator supplies it.
 *
 * <p>{@code enabled} is separate from being configured on purpose: a deployment sets a collector up before it
 * is ready to send, and switching the flow off should not mean deleting the destination and its credential.
 */
public record AuditExportSettings(String endpointUrl, String credential, boolean enabled) {
}
