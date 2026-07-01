package com.example.sso.audit;

/**
 * Immutable parameter object for {@link AuditService#record(AuditRecord)}: a single security-relevant
 * event — its type, the acting principal, the outcome, and optional free-form detail and remote IP.
 */
public record AuditRecord(String type, String principal, boolean success, String detail, String remoteIp) {
}
