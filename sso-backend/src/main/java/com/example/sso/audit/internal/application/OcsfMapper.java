package com.example.sso.audit.internal.application;

import com.example.sso.audit.AuditType;
import com.example.sso.audit.export.AuditExportRecord;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.function.Predicate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Renders an audit row as an OCSF event.
 *
 * <p><b>This is a published contract.</b> Once a collector parses it, an internal column rename must not
 * reach it and a new audit kind must not silently arrive as something vague — so the mapping is explicit per
 * type, and {@link #missingMappings} makes a gap fail at STARTUP, where somebody is still looking, rather
 * than in a SIEM query months later that quietly returns nothing.
 *
 * <p>The audit CATEGORY deliberately does not decide the class. {@code ADMIN} holds both account changes and
 * configuration edits, and {@code AUTHORIZATION} holds both privilege grants and CRUD on policy objects;
 * deriving from the category would file configuration edits into account analytics and leave privilege
 * grants where no rule looks for them.
 */
@Component
public class OcsfMapper {

    /** The OCSF schema version this shape was written against; pinned so a collector can branch on it. */
    private static final String SCHEMA_VERSION = "1.3.0";

    /** Emitted for a platform-tier event, so "no tenant" cannot be confused with "the tenant was omitted". */
    private static final String PLATFORM_TENANT = "platform";

    private static final Map<AuditType, OcsfActivity> MAPPING = new EnumMap<>(AuditType.class);

    static {
        MAPPING.put(AuditType.AUTH_ORGANIZATION, OcsfActivity.AUTH_OTHER);
        MAPPING.put(AuditType.AUTH_IDENTIFY, OcsfActivity.AUTH_OTHER);
        MAPPING.put(AuditType.AUTH_SUCCESS, OcsfActivity.LOGON);
        MAPPING.put(AuditType.AUTH_FAILURE, OcsfActivity.LOGON);
        MAPPING.put(AuditType.MFA_SUCCESS, OcsfActivity.AUTH_TICKET);
        MAPPING.put(AuditType.MFA_FAILURE, OcsfActivity.AUTH_TICKET);
        MAPPING.put(AuditType.MFA_LOCKED, OcsfActivity.ACCOUNT_LOCK);
        MAPPING.put(AuditType.REAUTH_SUCCESS, OcsfActivity.AUTH_TICKET);
        MAPPING.put(AuditType.REAUTH_FAILURE, OcsfActivity.AUTH_TICKET);
        MAPPING.put(AuditType.TOTP_ENROLLED, OcsfActivity.MFA_ENABLE);
        MAPPING.put(AuditType.TOTP_REMOVED, OcsfActivity.MFA_DISABLE);
        MAPPING.put(AuditType.SESSION_CREATED, OcsfActivity.LOGON);
        MAPPING.put(AuditType.SESSION_REVOKED, OcsfActivity.LOGOFF);
        MAPPING.put(AuditType.SESSION_EXPIRED_IDLE, OcsfActivity.LOGOFF);
        MAPPING.put(AuditType.SESSION_EXPIRED_ABSOLUTE, OcsfActivity.LOGOFF);
        MAPPING.put(AuditType.SESSION_CONCURRENT_EXPIRED, OcsfActivity.LOGOFF);
        MAPPING.put(AuditType.SESSION_CONTEXT_MISMATCH, OcsfActivity.AUTH_OTHER);
        MAPPING.put(AuditType.LOGOUT, OcsfActivity.LOGOFF);
        MAPPING.put(AuditType.OIDC_BACKCHANNEL_LOGOUT, OcsfActivity.LOGOFF);
        MAPPING.put(AuditType.SAML_SLO, OcsfActivity.LOGOFF);
        MAPPING.put(AuditType.SESSION_ADMIN_REVOKED, OcsfActivity.LOGOFF);
        MAPPING.put(AuditType.SESSION_TERMINATION_DEFERRED, OcsfActivity.AUTH_OTHER);
        MAPPING.put(AuditType.SESSION_TERMINATION_FAILED, OcsfActivity.AUTH_OTHER);
        MAPPING.put(AuditType.OIDC_AUTHORIZATION_REVOKED, OcsfActivity.LOGOFF);
        MAPPING.put(AuditType.IP_BLOCKED, OcsfActivity.AUTH_OTHER);
        MAPPING.put(AuditType.ADMIN_IP_BLOCKED, OcsfActivity.AUTH_OTHER);
        MAPPING.put(AuditType.ADMIN_ELEVATION_DENIED, OcsfActivity.AUTHORIZE_OTHER);
        MAPPING.put(AuditType.RESPONSE_ACTION_REFUSED, OcsfActivity.API_OTHER);
        MAPPING.put(AuditType.RESPONSE_BUDGET_EXHAUSTED, OcsfActivity.API_OTHER);
        MAPPING.put(AuditType.RATE_LIMITED, OcsfActivity.API_OTHER);
        MAPPING.put(AuditType.SAML_SSO_ISSUED, OcsfActivity.AUTH_TICKET);
        MAPPING.put(AuditType.SAML_STEPUP_REQUIRED, OcsfActivity.AUTH_OTHER);
        MAPPING.put(AuditType.OIDC_APP_ACCESS, OcsfActivity.AUTH_TICKET);
        MAPPING.put(AuditType.AUTHORIZATION_DENIED, OcsfActivity.AUTHORIZE_OTHER);
        MAPPING.put(AuditType.USER_PERMISSIONS_UPDATED, OcsfActivity.ASSIGN_PRIVILEGES);
        MAPPING.put(AuditType.ROLE_GRANT_EXPIRED, OcsfActivity.ASSIGN_PRIVILEGES);
        MAPPING.put(AuditType.PERMISSION_DENY_CREATED, OcsfActivity.ASSIGN_PRIVILEGES);
        MAPPING.put(AuditType.PERMISSION_DENY_LIFTED, OcsfActivity.ASSIGN_PRIVILEGES);
        MAPPING.put(AuditType.PERMISSION_DENY_LIFTED_BY_SYNC, OcsfActivity.ASSIGN_PRIVILEGES);
        MAPPING.put(AuditType.ROLE_CREATED, OcsfActivity.API_CREATE);
        MAPPING.put(AuditType.ROLE_UPDATED, OcsfActivity.API_UPDATE);
        MAPPING.put(AuditType.ROLE_DELETED, OcsfActivity.API_DELETE);
        MAPPING.put(AuditType.GROUP_ROLES_UPDATED, OcsfActivity.ASSIGN_PRIVILEGES);
        MAPPING.put(AuditType.GROUP_MANAGERS_UPDATED, OcsfActivity.ASSIGN_PRIVILEGES);
        MAPPING.put(AuditType.MAPPING_RULE_CREATED, OcsfActivity.API_CREATE);
        MAPPING.put(AuditType.MAPPING_RULE_UPDATED, OcsfActivity.API_UPDATE);
        MAPPING.put(AuditType.MAPPING_RULE_DELETED, OcsfActivity.API_DELETE);
        MAPPING.put(AuditType.MAPPING_RULE_APPLIED, OcsfActivity.ASSIGN_PRIVILEGES);
        MAPPING.put(AuditType.MAPPING_RULE_RETRACTED, OcsfActivity.ASSIGN_PRIVILEGES);
        MAPPING.put(AuditType.MAPPING_RULE_RETRACTION_REFUSED, OcsfActivity.AUTHORIZE_OTHER);
        MAPPING.put(AuditType.MAPPING_RULE_RECONCILE_STALLED, OcsfActivity.API_OTHER);
        MAPPING.put(AuditType.MAPPING_RULE_AUTHOR_UNAUTHORIZED, OcsfActivity.AUTHORIZE_OTHER);
        MAPPING.put(AuditType.MAPPING_RULE_LEGACY_AUTHOR, OcsfActivity.AUTHORIZE_OTHER);
        MAPPING.put(AuditType.MAPPING_RULE_DIRECTORY_SOURCE_UNAUTHORIZED, OcsfActivity.AUTHORIZE_OTHER);
        MAPPING.put(AuditType.APP_ASSIGNMENT_CHANGED, OcsfActivity.ATTACH_POLICY);
        MAPPING.put(AuditType.AUTH_POLICY_CREATED, OcsfActivity.API_CREATE);
        MAPPING.put(AuditType.AUTH_POLICY_UPDATED, OcsfActivity.API_UPDATE);
        MAPPING.put(AuditType.AUTH_POLICY_DELETED, OcsfActivity.API_DELETE);
        MAPPING.put(AuditType.SESSION_POLICY_CREATED, OcsfActivity.API_CREATE);
        MAPPING.put(AuditType.SESSION_POLICY_UPDATED, OcsfActivity.API_UPDATE);
        MAPPING.put(AuditType.SESSION_POLICY_DELETED, OcsfActivity.API_DELETE);
        MAPPING.put(AuditType.USER_CREATED, OcsfActivity.ACCOUNT_CREATE);
        MAPPING.put(AuditType.USER_UPDATED, OcsfActivity.ACCOUNT_CHANGE_OTHER);
        MAPPING.put(AuditType.USER_ENABLED, OcsfActivity.ACCOUNT_ENABLE);
        MAPPING.put(AuditType.USER_DISABLED, OcsfActivity.ACCOUNT_DISABLE);
        MAPPING.put(AuditType.USER_DELETED, OcsfActivity.ACCOUNT_DELETE);
        MAPPING.put(AuditType.USER_MFA_RESET, OcsfActivity.MFA_DISABLE);
        MAPPING.put(AuditType.ACCOUNT_HELD, OcsfActivity.ACCOUNT_LOCK);
        MAPPING.put(AuditType.ACCOUNT_HOLD_LIFTED, OcsfActivity.ACCOUNT_UNLOCK);
        MAPPING.put(AuditType.ACCOUNT_HOLD_EXPIRED, OcsfActivity.ACCOUNT_UNLOCK);
        MAPPING.put(AuditType.ORGANIZATION_CREATED, OcsfActivity.API_CREATE);
        MAPPING.put(AuditType.ORGANIZATION_UPDATED, OcsfActivity.API_UPDATE);
        MAPPING.put(AuditType.ORGANIZATION_DELETED, OcsfActivity.API_DELETE);
        MAPPING.put(AuditType.ORGANIZATION_MEMBER_ADDED, OcsfActivity.ASSIGN_GROUPS);
        MAPPING.put(AuditType.ORGANIZATION_MEMBER_REMOVED, OcsfActivity.ASSIGN_GROUPS);
        MAPPING.put(AuditType.ORGANIZATION_CONTEXT_ENTERED, OcsfActivity.AUTHORIZE_OTHER);
        MAPPING.put(AuditType.ATTRIBUTE_CHANGED, OcsfActivity.ACCOUNT_CHANGE_OTHER);
        MAPPING.put(AuditType.ATTRIBUTE_DEFINITION_CHANGED, OcsfActivity.API_UPDATE);
        MAPPING.put(AuditType.AUDIT_EXPORT_CONFIGURED, OcsfActivity.API_UPDATE);
        MAPPING.put(AuditType.RESOURCE_CHANGED, OcsfActivity.API_UPDATE);
        MAPPING.put(AuditType.OIDC_CLIENT_CREATED, OcsfActivity.API_CREATE);
        MAPPING.put(AuditType.OIDC_CLIENT_UPDATED, OcsfActivity.API_UPDATE);
        MAPPING.put(AuditType.OIDC_CLIENT_DELETED, OcsfActivity.API_DELETE);
        MAPPING.put(AuditType.RELYING_PARTY_CREATED, OcsfActivity.API_CREATE);
        MAPPING.put(AuditType.RELYING_PARTY_UPDATED, OcsfActivity.API_UPDATE);
        MAPPING.put(AuditType.RELYING_PARTY_DELETED, OcsfActivity.API_DELETE);
        MAPPING.put(AuditType.BRANDING_CHANGED, OcsfActivity.API_UPDATE);
        MAPPING.put(AuditType.PORTAL_SETTINGS_CHANGED, OcsfActivity.API_UPDATE);
        MAPPING.put(AuditType.PROFILE_MAPPING_CHANGED, OcsfActivity.API_UPDATE);
        MAPPING.put(AuditType.TENANT_ONBOARDING_STARTED, OcsfActivity.API_CREATE);
        MAPPING.put(AuditType.TENANT_ADMIN_REINVITED, OcsfActivity.PASSWORD_RESET);
        MAPPING.put(AuditType.EMAIL_TEMPLATE_CHANGED, OcsfActivity.API_UPDATE);
        MAPPING.put(AuditType.SMTP_SETTINGS_CHANGED, OcsfActivity.API_UPDATE);
        MAPPING.put(AuditType.SMS_SETTINGS_CHANGED, OcsfActivity.API_UPDATE);
        MAPPING.put(AuditType.SCIM_TOKEN_CHANGED, OcsfActivity.API_UPDATE);
        MAPPING.put(AuditType.NETWORK_ZONE_CHANGED, OcsfActivity.API_UPDATE);
        MAPPING.put(AuditType.SIGNING_KEY_ROTATED, OcsfActivity.API_UPDATE);
        MAPPING.put(AuditType.DIRECTORY_CONNECTOR_CHANGED, OcsfActivity.API_UPDATE);
        MAPPING.put(AuditType.IDENTITY_PROVIDER_CHANGED, OcsfActivity.API_UPDATE);
        MAPPING.put(AuditType.DIRECTORY_SYNC_RUN, OcsfActivity.API_OTHER);
        MAPPING.put(AuditType.SMS_SEND_FAILED, OcsfActivity.API_OTHER);
        MAPPING.put(AuditType.NOTIFICATION_DROPPED, OcsfActivity.API_OTHER);
        MAPPING.put(AuditType.SERVER_ERROR, OcsfActivity.API_OTHER);
    }

    private final String productName;

    OcsfMapper(@Value("${sso.audit.export.product-name}") String productName) {
        this.productName = productName;
    }

    /**
     * Audit kinds this mapper cannot render, given a predicate for "is mapped".
     *
     * <p>Takes the predicate so the guard itself can be tested — a completeness check nobody has watched fail
     * is a comment. {@link AuditExportStartupCheck} calls it with the real table.
     */
    static List<AuditType> missingMappings(Predicate<AuditType> mapped) {
        return Arrays.stream(AuditType.values()).filter(type -> !mapped.test(type)).toList();
    }

    /** The kinds with no entry in the table above. */
    static List<AuditType> missingMappings() {
        return missingMappings(MAPPING::containsKey);
    }

    /** One audit row as an OCSF event, ready to serialise. */
    public Map<String, Object> toOcsf(AuditExportRecord row) {
        OcsfActivity activity = MAPPING.get(AuditType.valueOf(row.type()));
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("class_uid", activity.classUid());
        event.put("activity_id", activity.activityId());
        event.put("type_uid", activity.typeUid());
        event.put("time", row.occurredAt().toEpochMilli());
        event.put("severity_id", severityId(row.severity()));
        event.put("status_id", row.success() ? 1 : 2);          // Success / Failure
        event.put("status_detail", row.reason());               // a structured code, already non-revealing
        event.put("message", row.detail());
        event.put("metadata", metadata(row));
        event.put("actor", actor(row));
        event.put("src_endpoint", Map.of("ip", nullToEmpty(row.remoteIp())));
        if (row.userAgent() != null) {
            event.put("http_request", Map.of("user_agent", row.userAgent()));
        }
        if (row.device() != null) {
            event.put("device", Map.of("type", row.device()));
        }
        event.put("unmapped", unmapped(row));
        return event;
    }

    private Map<String, Object> metadata(AuditExportRecord row) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        // The dedup key. Delivery is at-least-once — a retry repeats a batch — so the collector needs a
        // stable identity per event rather than exactly-once semantics over HTTP.
        metadata.put("uid", String.valueOf(row.id()));
        metadata.put("version", SCHEMA_VERSION);
        metadata.put("product", Map.of("name", productName, "vendor_name", productName));
        if (row.requestId() != null) {
            metadata.put("correlation_uid", row.requestId());
        }
        // THE TENANT. OCSF has no home for it, and this is the single most important note for whoever
        // configures the collector: a multi-tenant IdP shipping into one index means a query with no tenant
        // predicate crosses tenants.
        metadata.put("labels", Map.of("tenant_id", row.orgId() == null ? PLATFORM_TENANT : row.orgId().toString()));
        return metadata;
    }

    /**
     * Who acted. An ANONYMOUS actor is a CLAIM — a failed-login username nobody proved control of — so it
     * carries the name and no account identity: OCSF user type 0 (Unknown) rather than 1 (User). Flattening
     * the two would attribute events to a principal this IdP never authenticated.
     */
    private Map<String, Object> actor(AuditExportRecord row) {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("uid", row.actorId() == null ? null : row.actorId().toString());
        user.put("name", row.actorDisplay() == null ? row.principal() : row.actorDisplay());
        user.put("email_addr", row.actorEmail());
        user.put("type_id", userTypeId(row.actorType()));
        return Map.of("user", user);
    }

    /** OCSF user type: 1 User, 2 Admin, 3 System, 0 Unknown for a principal nobody authenticated. */
    private int userTypeId(String actorType) {
        if (actorType == null) {
            return 0;
        }
        return switch (actorType) {
            case "USER" -> 1;
            case "SERVICE", "SYSTEM" -> 3;
            default -> 0;   // ANONYMOUS — a claimed name, not an identity
        };
    }

    /** INFO -> Informational, WARNING -> Medium, CRITICAL -> Critical. */
    private int severityId(String severity) {
        return switch (severity == null ? "INFO" : severity) {
            case "CRITICAL" -> 5;
            case "WARNING" -> 3;
            default -> 1;
        };
    }

    /**
     * What OCSF has no field for and must not be dropped: this IdP's own event name and the entity the action
     * was performed ON. The subject is half of every "who did what to whom" question, and the class/activity
     * pair alone cannot express it.
     */
    private Map<String, Object> unmapped(AuditExportRecord row) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("audit_type", row.type());
        extra.put("audit_category", row.category());
        if (row.subjectId() != null) {
            extra.put("subject_type", row.subjectType());
            extra.put("subject_id", row.subjectId());
        }
        return extra;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
