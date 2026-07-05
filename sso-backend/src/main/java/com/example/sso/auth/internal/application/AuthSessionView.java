package com.example.sso.auth.internal.application;

import java.util.List;

/**
 * Current authentication state for the SPA. {@code next} is ORGANIZATION (enter the tenant slug),
 * IDENTIFY (enter email), FACTOR (complete the current policy step — choose one of {@code pendingFactors}),
 * or DONE. {@code org} is the active organization's slug once resolved (tenant-first entry), else null.
 * {@code mfaEnrollmentAllowed} = whether an un-enrolled factor may be set up during this login.
 */
public record AuthSessionView(boolean authenticated, String username, boolean totpEnrolled,
                              boolean fido2Enrolled, List<String> factors, List<String> roles,
                              List<String> permissions, String next, List<String> pendingFactors,
                              boolean mfaEnrollmentAllowed, String org) {

    /** {@code next}: the SPA must collect the organization (tenant) slug before anything else. */
    public static final String NEXT_ORGANIZATION = "ORGANIZATION";
    /** {@code next}: the SPA must collect the account identifier (email) before any factor. */
    public static final String NEXT_IDENTIFY = "IDENTIFY";
    /** {@code next}: the SPA must complete the current policy step (one of {@code pendingFactors}). */
    public static final String NEXT_FACTOR = "FACTOR";
    /** {@code next}: the authentication policy is fully satisfied. */
    public static final String NEXT_DONE = "DONE";

    /** No organization resolved yet — the SPA must collect the tenant slug (tenant-first entry). */
    public static AuthSessionView organizationPending(boolean mfaEnrollmentAllowed) {
        return new AuthSessionView(false, null, false, false, List.of(), List.of(), List.of(),
                NEXT_ORGANIZATION, List.of(), mfaEnrollmentAllowed, null);
    }

    /** Organization resolved, no identified user yet — the SPA must collect the email. */
    public static AuthSessionView identifyPending(String org, boolean mfaEnrollmentAllowed) {
        return new AuthSessionView(false, null, false, false, List.of(), List.of(), List.of(),
                NEXT_IDENTIFY, List.of(), mfaEnrollmentAllowed, org);
    }

    /** The policy is fully satisfied — the session is complete. */
    public static AuthSessionView complete(String username, boolean totpEnrolled, boolean fido2Enrolled,
                                           List<String> factors, List<String> roles, List<String> permissions,
                                           boolean mfaEnrollmentAllowed, String org) {
        return new AuthSessionView(true, username, totpEnrolled, fido2Enrolled, factors, roles, permissions,
                NEXT_DONE, List.of(), mfaEnrollmentAllowed, org);
    }

    /** A current policy step remains — the SPA completes one of {@code pendingFactors}. */
    public static AuthSessionView pending(String username, boolean totpEnrolled, boolean fido2Enrolled,
                                          List<String> factors, List<String> roles, List<String> permissions,
                                          List<String> pendingFactors, boolean mfaEnrollmentAllowed, String org) {
        return new AuthSessionView(false, username, totpEnrolled, fido2Enrolled, factors, roles, permissions,
                NEXT_FACTOR, pendingFactors, mfaEnrollmentAllowed, org);
    }
}
