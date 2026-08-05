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
    RESET;

    /**
     * Whether a tenant may replace this screen's TITLE.
     *
     * <p>Only the consent screen says no, and the reason is not cosmetic: its title names the client asking
     * for access — the one thing telling a user WHICH application they are authorizing. A tenant admin able to
     * replace it would put a chosen heading above a genuine scope list and redirect host, on the IdP's real
     * origin and certificate.
     *
     * <p>The answer lives HERE, on the type that closes the set, rather than in the component that draws the
     * screen. A rule about what a tenant may SAY belongs to the thing that owns the screens: the renderer can
     * be replaced, duplicated, or joined by a second one, and a rule enforced only there is a rule the write
     * path never applies. A sixth screen has to state its own answer instead of inheriting a permissive one.
     */
    public boolean allowsTenantHeadline() {
        return this != CONSENT;
    }
}
