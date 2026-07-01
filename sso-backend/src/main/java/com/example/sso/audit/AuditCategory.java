package com.example.sso.audit;

/**
 * Coarse classification of an audit event, so the admin portal can show the log by category. Derived
 * from the event {@code type} at record time (see {@link #of}); the mapping is the single place that
 * grows as new event kinds are added (sessions, access, app launches, user self-service, …).
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
    SYSTEM;

    /** Classifies an event by its {@code type}. Access-control keywords win first, then the rest. */
    public static AuditCategory of(String type) {
        if (type == null) {
            return SYSTEM;
        }
        String t = type.toUpperCase();

        if (t.equals("AUTHORIZATION_DENIED") || t.contains("ROLE") || t.contains("PERMISSION")
                || t.startsWith("GROUP")) {
            return AUTHORIZATION;
        }
        if (t.startsWith("SESSION") || t.equals("SESSION_REVOKED")) {
            return SESSION;
        }
        if (t.startsWith("SAML") || t.startsWith("OIDC") || t.contains("SSO") || t.contains("APP_")) {
            return APP_ACCESS;
        }
        if (t.contains("IP_BLOCKED") || t.equals("RATE_LIMITED")) {
            return ACCESS;
        }
        if (t.startsWith("AUTH") || t.startsWith("MFA") || t.startsWith("TOTP") || t.startsWith("REAUTH")
                || t.startsWith("FIDO") || t.startsWith("WEBAUTHN")) {
            return AUTHENTICATION;
        }
        if (t.startsWith("USER") || t.startsWith("ADMIN")) {
            return ADMIN;
        }
        if (t.startsWith("PROFILE") || t.startsWith("PASSKEY") || t.startsWith("SELF")) {
            return USER_ACTION;
        }
        return SYSTEM;
    }
}
