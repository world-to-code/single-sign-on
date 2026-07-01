package com.example.sso.auth.internal.application;

import java.util.List;

/**
 * Current authentication state for the SPA. {@code next} is IDENTIFY (enter email), FACTOR
 * (complete the current policy step — choose one of {@code pendingFactors}), or DONE.
 * {@code mfaEnrollmentAllowed} = whether an un-enrolled factor may be set up during this login.
 */
public record AuthSessionView(boolean authenticated, String username, boolean totpEnrolled,
                              boolean fido2Enrolled, List<String> factors, List<String> roles,
                              String next, List<String> pendingFactors, boolean mfaEnrollmentAllowed) {
}
