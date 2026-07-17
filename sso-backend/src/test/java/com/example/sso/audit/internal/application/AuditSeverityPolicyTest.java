package com.example.sso.audit.internal.application;

import com.example.sso.audit.AuditSeverity;
import com.example.sso.audit.AuditType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The severity policy derives a SIEM triage level from (type, outcome): routine successes are INFO, any
 * failed/denied outcome is at least WARNING, and a fixed set of security-significant kinds is CRITICAL
 * regardless of the {@code success} flag.
 */
class AuditSeverityPolicyTest {

    private final AuditSeverityPolicy policy = new AuditSeverityPolicy();

    // Expected classification, duplicated here on purpose: removing a member from the production set must force a
    // failing test (a silent severity downgrade defeats the SIEM alerting the (severity, occurred_at) index serves).
    private static final Set<AuditType> CRITICAL = EnumSet.of(
            AuditType.SERVER_ERROR, AuditType.SIGNING_KEY_ROTATED, AuditType.MFA_LOCKED,
            AuditType.IP_BLOCKED, AuditType.ADMIN_IP_BLOCKED, AuditType.ADMIN_ELEVATION_DENIED,
            AuditType.SESSION_CONTEXT_MISMATCH, AuditType.SESSION_TERMINATION_FAILED,
            AuditType.MAPPING_RULE_AUTHOR_UNAUTHORIZED);
    private static final Set<AuditType> WARNING = EnumSet.of(
            AuditType.AUTHORIZATION_DENIED, AuditType.RATE_LIMITED,
            AuditType.SAML_STEPUP_REQUIRED, AuditType.SESSION_TERMINATION_DEFERRED);

    @Test
    void aRoutineSuccessIsInfo() {
        assertThat(policy.severityOf(AuditType.AUTH_SUCCESS, true)).isEqualTo(AuditSeverity.INFO);
        assertThat(policy.severityOf(AuditType.SESSION_CREATED, true)).isEqualTo(AuditSeverity.INFO);
    }

    @ParameterizedTest
    @EnumSource(AuditType.class)
    void classificationIsTableDrivenForEveryType(AuditType type) {
        AuditSeverity expected = CRITICAL.contains(type) ? AuditSeverity.CRITICAL
                : WARNING.contains(type) ? AuditSeverity.WARNING
                : AuditSeverity.INFO;
        // On a success, each type resolves to its base classification...
        assertThat(policy.severityOf(type, true)).isEqualTo(expected);
        // ...and a failure is escalated to at least WARNING (CRITICAL types stay CRITICAL).
        AuditSeverity onFailure = CRITICAL.contains(type) ? AuditSeverity.CRITICAL : AuditSeverity.WARNING;
        assertThat(policy.severityOf(type, false)).isEqualTo(onFailure);
    }
}
