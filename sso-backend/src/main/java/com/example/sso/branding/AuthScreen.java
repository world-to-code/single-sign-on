package com.example.sso.branding;

/**
 * A sign-in screen a tenant can word for itself. Code-bound rather than data: each constant names a screen
 * the SPA implements, so adding one is a change to both sides and not a row somebody can invent.
 */
public enum AuthScreen {

    /** Identifier + password / passwordless entry. */
    LOGIN,

    /** A second factor during login. */
    MFA,

    /** A deliberate re-authentication for a sensitive action (RFC 9470 step-up). */
    STEPUP,

    /** The OAuth2 authorization-consent screen. */
    CONSENT,

    /** Setting a password: a forced first-login reset, or an invitation being redeemed. */
    RESET
}
