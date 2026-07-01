package com.example.sso.audit;

/**
 * Coarse classification of an audit event, so the admin portal can show the log by category. Each
 * {@link AuditType} declares its category, so classification is table-driven rather than string-matched.
 */
public enum AuditCategory {

    /** Login, MFA/factor verification, step-up re-authentication. */
    AUTHENTICATION,
    /** Access-control changes and decisions: role/permission/group-manager edits, authorization denials. */
    AUTHORIZATION,
    /** Session lifecycle: creation, revocation, expiry. */
    SESSION,
    /** Network / rate access decisions (IP blocks, throttling). */
    ACCESS,
    /** A user's own self-service actions (profile, credentials). */
    USER_ACTION,
    /** Application sign-on: OIDC/SAML SSO issued, app launches. */
    APP_ACCESS,
    /** Administrative operations on other users/objects that are not primarily access-control. */
    ADMIN,
    /** Platform/system events: key rotation, provisioning, server errors. */
    SYSTEM
}
