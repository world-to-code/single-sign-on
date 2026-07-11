package com.example.sso.authpolicy.factor;

/** Authentication factor types selectable in an authentication-policy step. */
public enum AuthFactor {
    PASSWORD("FACTOR_PASSWORD"),
    TOTP("FACTOR_TOTP"),
    EMAIL("FACTOR_EMAIL"),
    FIDO2("FACTOR_WEBAUTHN");

    private final String authority;

    AuthFactor(String authority) {
        this.authority = authority;
    }

    /** The granted-authority string recorded in the session when this factor is satisfied. */
    public String authority() {
        return authority;
    }
}
