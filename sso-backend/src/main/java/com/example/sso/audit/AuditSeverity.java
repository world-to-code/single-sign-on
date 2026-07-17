package com.example.sso.audit;

/**
 * Triage level for SIEM alerting, derived from the event type and outcome. Ordered
 * {@code INFO < WARNING < CRITICAL} (ordinal-significant): routine successes are INFO, failed or
 * denied attempts are at least WARNING, and security-significant events (key rotation, lockouts,
 * blocked access, revocation failures) are CRITICAL regardless of outcome.
 */
public enum AuditSeverity {
    INFO, WARNING, CRITICAL
}
