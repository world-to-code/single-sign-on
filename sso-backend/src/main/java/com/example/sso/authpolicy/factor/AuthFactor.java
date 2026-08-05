package com.example.sso.authpolicy.factor;

/** Authentication factor types selectable in an authentication-policy step. */
public enum AuthFactor {
    PASSWORD("FACTOR_PASSWORD"),
    TOTP("FACTOR_TOTP"),
    EMAIL("FACTOR_EMAIL"),
    // Ordinal position is deliberate: AuthStateService picks the SPA's default pending factor by ordinal, so
    // SMS sits beside EMAIL (both are code-to-a-contact factors) rather than after the passkey.
    SMS("FACTOR_SMS"),
    FIDO2("FACTOR_WEBAUTHN");

    private final String authority;

    AuthFactor(String authority) {
        this.authority = authority;
    }

    /** The granted-authority string recorded in the session when this factor is satisfied. */
    public String authority() {
        return authority;
    }

    /**
     * Whether {@code authority} is one of THIS IdP's factors.
     *
     * <p>Exists because the {@code FACTOR_} prefix is not a safe test for that: Spring Security defines its
     * own authorities in the same namespace ({@code FACTOR_AUTHORIZATION_CODE}, {@code FACTOR_SAML_RESPONSE},
     * {@code FACTOR_BEARER}, …), minted by configurers this deployment does not currently wire but a
     * refactor could. Counting one of those toward {@code acr} would push a single-factor login to
     * {@code mfa} — past the admin elevation gate — while naming nothing in {@code amr}.
     */
    public static boolean isKnownAuthority(String authority) {
        for (AuthFactor factor : values()) {
            if (factor.authority.equals(authority)) {
                return true;
            }
        }
        return false;
    }
}
