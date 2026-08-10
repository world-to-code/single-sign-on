package com.example.sso.audit.export;

/**
 * Where to ship the audit trail, as an administrator supplies it.
 *
 * <p>{@code enabled} is separate from being configured on purpose: a deployment sets a collector up before it
 * is ready to send, and switching the flow off should not mean deleting the destination and its credential.
 *
 * <p>{@code includePii} decides whether the actor's and client's personal identifiers travel. It is a separate
 * decision from where to send, because it is a separate PERMISSION: the console gates those fields behind
 * {@code audit:read:pii}, and {@code audit:export} does not imply it.
 */
public record AuditExportSettings(String endpointUrl, String credential, boolean enabled, boolean includePii) {
}
