package com.example.sso.audit;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The catalog of audit event kinds. Each value declares its {@link AuditCategory}, so classification is
 * table-driven (no string matching) and the admin log can be browsed by category. Recorders reference
 * these values instead of hardcoding type strings; the persisted {@code type} is the enum name.
 */
@Getter
@RequiredArgsConstructor
public enum AuditType {

    // Authentication
    AUTH_ORGANIZATION(AuditCategory.AUTHENTICATION),
    AUTH_IDENTIFY(AuditCategory.AUTHENTICATION),
    AUTH_SUCCESS(AuditCategory.AUTHENTICATION),
    AUTH_FAILURE(AuditCategory.AUTHENTICATION),
    MFA_SUCCESS(AuditCategory.AUTHENTICATION),
    MFA_FAILURE(AuditCategory.AUTHENTICATION),
    MFA_LOCKED(AuditCategory.AUTHENTICATION),
    REAUTH_SUCCESS(AuditCategory.AUTHENTICATION),
    REAUTH_FAILURE(AuditCategory.AUTHENTICATION),

    // User self-service actions
    TOTP_ENROLLED(AuditCategory.USER_ACTION),
    TOTP_REMOVED(AuditCategory.USER_ACTION),

    // Session lifecycle
    SESSION_CREATED(AuditCategory.SESSION),
    SESSION_REVOKED(AuditCategory.SESSION),
    SESSION_EXPIRED_IDLE(AuditCategory.SESSION),
    SESSION_EXPIRED_ABSOLUTE(AuditCategory.SESSION),
    SESSION_CONCURRENT_EXPIRED(AuditCategory.SESSION),
    SESSION_CONTEXT_MISMATCH(AuditCategory.SESSION),
    LOGOUT(AuditCategory.SESSION),
    OIDC_BACKCHANNEL_LOGOUT(AuditCategory.SESSION),
    SAML_SLO(AuditCategory.SESSION),
    SESSION_ADMIN_REVOKED(AuditCategory.SESSION),
    SESSION_TERMINATION_DEFERRED(AuditCategory.SESSION), // in-thread termination retries exhausted; handed to the durable sweep to re-drive
    SESSION_TERMINATION_FAILED(AuditCategory.SESSION), // durable retries exhausted too — the session may outlive the access change until its TTL

    // Network / rate access
    IP_BLOCKED(AuditCategory.ACCESS),
    ADMIN_IP_BLOCKED(AuditCategory.ACCESS),
    ADMIN_ELEVATION_DENIED(AuditCategory.ACCESS),
    RATE_LIMITED(AuditCategory.ACCESS),

    // Application sign-on
    SAML_SSO_ISSUED(AuditCategory.APP_ACCESS),
    SAML_STEPUP_REQUIRED(AuditCategory.APP_ACCESS),
    OIDC_APP_ACCESS(AuditCategory.APP_ACCESS),

    // Access-control decisions and administration
    AUTHORIZATION_DENIED(AuditCategory.AUTHORIZATION),
    USER_PERMISSIONS_UPDATED(AuditCategory.AUTHORIZATION),
    ROLE_CREATED(AuditCategory.AUTHORIZATION),
    ROLE_UPDATED(AuditCategory.AUTHORIZATION),
    ROLE_DELETED(AuditCategory.AUTHORIZATION),
    GROUP_ROLES_UPDATED(AuditCategory.AUTHORIZATION),
    GROUP_MANAGERS_UPDATED(AuditCategory.AUTHORIZATION),
    MAPPING_RULE_CREATED(AuditCategory.AUTHORIZATION),
    MAPPING_RULE_UPDATED(AuditCategory.AUTHORIZATION),
    MAPPING_RULE_DELETED(AuditCategory.AUTHORIZATION),
    MAPPING_RULE_APPLIED(AuditCategory.AUTHORIZATION),
    MAPPING_RULE_RETRACTED(AuditCategory.AUTHORIZATION),
    MAPPING_RULE_AUTHOR_UNAUTHORIZED(AuditCategory.AUTHORIZATION), // rule outlived its author's grant authority — grant skipped
    MAPPING_RULE_LEGACY_AUTHOR(AuditCategory.AUTHORIZATION),       // a grant by a rule with no recorded author (pre-V97/system)

    // Administrative operations on users
    USER_CREATED(AuditCategory.ADMIN),
    USER_UPDATED(AuditCategory.ADMIN),
    USER_ENABLED(AuditCategory.ADMIN),
    USER_DISABLED(AuditCategory.ADMIN),
    USER_DELETED(AuditCategory.ADMIN),
    USER_MFA_RESET(AuditCategory.ADMIN),

    // Administrative operations on organizations (tenants)
    ORGANIZATION_CREATED(AuditCategory.ADMIN),
    ORGANIZATION_UPDATED(AuditCategory.ADMIN),
    ORGANIZATION_DELETED(AuditCategory.ADMIN),
    ORGANIZATION_MEMBER_ADDED(AuditCategory.ADMIN),
    ORGANIZATION_MEMBER_REMOVED(AuditCategory.ADMIN),
    ORGANIZATION_CONTEXT_ENTERED(AuditCategory.ADMIN),

    // Administrative configuration changes recorded by the @Audited interceptor (the request method+path
    // distinguishes create vs delete for the coarse *_CHANGED kinds)
    ATTRIBUTE_CHANGED(AuditCategory.ADMIN),
    AUTH_POLICY_CREATED(AuditCategory.AUTHORIZATION),
    AUTH_POLICY_UPDATED(AuditCategory.AUTHORIZATION),
    AUTH_POLICY_DELETED(AuditCategory.AUTHORIZATION),
    SESSION_POLICY_CREATED(AuditCategory.SESSION),
    SESSION_POLICY_UPDATED(AuditCategory.SESSION),
    SESSION_POLICY_DELETED(AuditCategory.SESSION),
    RESOURCE_CHANGED(AuditCategory.ADMIN),
    OIDC_CLIENT_CREATED(AuditCategory.ADMIN),
    OIDC_CLIENT_UPDATED(AuditCategory.ADMIN),
    OIDC_CLIENT_DELETED(AuditCategory.ADMIN),
    RELYING_PARTY_CREATED(AuditCategory.ADMIN),
    RELYING_PARTY_UPDATED(AuditCategory.ADMIN),
    RELYING_PARTY_DELETED(AuditCategory.ADMIN),
    SCIM_TOKEN_CHANGED(AuditCategory.ADMIN),
    NETWORK_ZONE_CHANGED(AuditCategory.ADMIN),
    APP_ASSIGNMENT_CHANGED(AuditCategory.AUTHORIZATION),
    SIGNING_KEY_ROTATED(AuditCategory.ADMIN),

    // Platform / system
    SERVER_ERROR(AuditCategory.SYSTEM);

    private final AuditCategory category;
}
