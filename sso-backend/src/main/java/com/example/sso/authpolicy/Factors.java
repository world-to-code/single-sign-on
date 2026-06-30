package com.example.sso.authpolicy;

/**
 * Authority strings for authentication factors and the MFA-complete marker. Factor authorities
 * derive from {@link AuthFactor} (the single source of truth) so they cannot drift.
 */
public final class Factors {

    public static final String PASSWORD = AuthFactor.PASSWORD.authority();
    public static final String TOTP = AuthFactor.TOTP.authority();
    public static final String EMAIL = AuthFactor.EMAIL.authority();
    public static final String FIDO2 = AuthFactor.FIDO2.authority();

    /** Granted once the user's resolved authentication policy is fully satisfied. */
    public static final String MFA_COMPLETE = "MFA_COMPLETE";

    /** Marker-authority prefix carrying the authentication time (epoch seconds) for the OIDC
     *  {@code auth_time} claim; serializes safely through the JDBC authorization store. */
    public static final String AUTH_TIME_PREFIX = "AUTH_TIME_";

    /** Marker-authority prefix carrying the time of a DELIBERATE step-up re-auth (epoch seconds),
     *  distinct from login {@link #AUTH_TIME_PREFIX}. Set only on /reauth success; surfaced as the
     *  {@code stepup_time} claim so the admin elevation gate can require a recent re-authentication
     *  (not merely a recent login). */
    public static final String STEPUP_TIME_PREFIX = "STEPUP_TIME_";

    private Factors() {
    }
}
