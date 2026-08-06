package com.example.sso.authpolicy.factor;

/**
 * Authority strings for authentication factors and the MFA-complete marker. Factor authorities
 * derive from {@link AuthFactor} (the single source of truth) so they cannot drift.
 */
public final class Factors {

    /**
     * Common prefix of every {@link AuthFactor} authority.
     *
     * <p>NOT public, and not the way to ask "is this a factor". Spring Security owns this namespace too and
     * mints eight authorities of its own in it, so a prefix test answers a question nobody asked: it says
     * "something wrote FACTOR_ here", not "this IdP proved a factor". Asking it by prefix let a foreign
     * authority skip the passwordless gate and inflate the acr level. Use {@link AuthFactor#isKnownAuthority}.
     */
    static final String FACTOR_PREFIX = "FACTOR_";

    public static final String PASSWORD = AuthFactor.PASSWORD.authority();
    public static final String TOTP = AuthFactor.TOTP.authority();
    public static final String EMAIL = AuthFactor.EMAIL.authority();
    public static final String SMS = AuthFactor.SMS.authority();
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
    /**
     * Marks a session whose primary factor was satisfied by an UPSTREAM identity provider rather than by a
     * credential this IdP holds. Deliberately not {@code FACTOR_}-prefixed: it is not a factor, and anything
     * carrying that prefix is counted as one. Surfaced to relying parties as the RFC 8176 {@code fed} method,
     * so an RP can tell "the user proved themselves to their own IdP" from "this IdP verified a password".
     */
    public static final String FEDERATED = "AUTH_FEDERATED";

    /**
     * Marks a session whose passkey was a SYNCED one — the credential reported itself backup-eligible, so the
     * platform copies the private key into the user's account keychain and it is not bound to any one device.
     * Not {@code FACTOR_}-prefixed for the same reason as {@link #FEDERATED}: it is not a factor, and counting
     * it would let a single passkey claim two-factor authentication.
     *
     * <p>It exists because RFC 8176 distinguishes a hardware-secured key ({@code hwk}) from a software-secured
     * one ({@code swk}), and a relying party gating a high-assurance action on a physical key acts on the
     * difference. Absent this marker the passkey was device-bound, which is what {@code hwk} claims.
     */
    public static final String SOFTWARE_BACKED_PASSKEY = "PASSKEY_SOFTWARE_BACKED";

    public static final String SID_PREFIX = "SID_";

    /** Marker-authority prefix carrying the id of the organization (tenant) the session logged into,
     *  surfaced as the {@code org} claim (OIDC) / attribute (SAML) and used to bind the request's tenant
     *  context. Set at login completion from the tenant-first entry step; carried across re-auth. */
    public static final String ORG_PREFIX = "ORG_";

    private Factors() {
    }
}
