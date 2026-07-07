package com.example.sso.authpolicy;

/**
 * Authority strings for authentication factors and the MFA-complete marker. Factor authorities
 * derive from {@link AuthFactor} (the single source of truth) so they cannot drift.
 */
public final class Factors {

    /** Common prefix of every {@link AuthFactor} authority; used to select the granted-factor markers. */
    public static final String FACTOR_PREFIX = "FACTOR_";

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

    /** Marker-authority prefix carrying this OP session's stable id, surfaced as the OIDC {@code sid}
     *  claim so back-channel logout can target the exact session (not every session of the subject).
     *  Generated once at login completion and carried across re-auth; mirrors the OIDC_SID session attribute. */
    public static final String SID_PREFIX = "SID_";

    /** Marker-authority prefix carrying the id of the organization (tenant) the session logged into,
     *  surfaced as the {@code org} claim (OIDC) / attribute (SAML) and used to bind the request's tenant
     *  context. Set at login completion from the tenant-first entry step; carried across re-auth. */
    public static final String ORG_PREFIX = "ORG_";

    /** Marker-authority prefix carrying the id of the customer (고객사) whose CONSOLE the session logged into —
     *  a customer-level session that manages the customer's orgs and drills into them, distinct from an
     *  {@code ORG_} session bound to a single org. Set at login completion from the customer-first entry step. */
    public static final String CUSTOMER_PREFIX = "CUSTOMER_";

    private Factors() {
    }
}
