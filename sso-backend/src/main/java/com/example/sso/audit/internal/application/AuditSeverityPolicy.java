package com.example.sso.audit.internal.application;

import com.example.sso.audit.AuditSeverity;
import com.example.sso.audit.AuditType;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/**
 * Derives a SIEM triage {@link AuditSeverity} from an event's type and outcome — table-driven, the
 * same way {@link AuditType} carries its {@code AuditCategory}. Security-significant kinds are always
 * CRITICAL; any failed/denied outcome is at least WARNING; everything else is INFO.
 */
@Component
public class AuditSeverityPolicy {

    /** Events that always warrant alerting regardless of the {@code success} flag. */
    private static final Set<AuditType> CRITICAL = EnumSet.of(
            AuditType.SERVER_ERROR,
            AuditType.SIGNING_KEY_ROTATED,
            AuditType.MFA_LOCKED,
            AuditType.IP_BLOCKED,
            AuditType.ADMIN_IP_BLOCKED,
            AuditType.ADMIN_ELEVATION_DENIED,
            AuditType.SESSION_CONTEXT_MISMATCH,
            AuditType.SESSION_TERMINATION_FAILED,
            AuditType.MAPPING_RULE_AUTHOR_UNAUTHORIZED);

    /** Events that are noteworthy even when recorded as a success (a denial IS the event). */
    private static final Set<AuditType> WARNING = EnumSet.of(
            AuditType.AUTHORIZATION_DENIED,
            AuditType.RATE_LIMITED,
            AuditType.SAML_STEPUP_REQUIRED,
            AuditType.SESSION_TERMINATION_DEFERRED);

    public AuditSeverity severityOf(AuditType type, boolean success) {
        if (CRITICAL.contains(type)) {
            return AuditSeverity.CRITICAL;
        }
        if (!success || WARNING.contains(type)) {
            return AuditSeverity.WARNING;
        }
        return AuditSeverity.INFO;
    }
}
