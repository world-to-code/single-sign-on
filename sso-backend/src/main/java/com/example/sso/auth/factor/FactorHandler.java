package com.example.sso.auth.factor;

import com.example.sso.authpolicy.AuthFactor;
import com.example.sso.user.AppUser;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Strategy for one authentication factor. Adding a new factor means adding one implementation
 * (plus the {@link AuthFactor} enum constant) — the auth API dispatches generically over these,
 * so endpoints and the policy engine need no per-factor branches (Open/Closed).
 */
public interface FactorHandler {

    AuthFactor factor();

    /** Whether the user has the prerequisite enrollment for this factor (e.g. a registered TOTP/passkey). */
    default boolean isEnrolled(AppUser user) {
        return true;
    }

    /** Optional pre-step: issue an enrollment/challenge (QR, WebAuthn options) or send a code. */
    default FactorChallenge prepare(AppUser user, HttpServletRequest request) {
        return FactorChallenge.none();
    }

    /** Verifies the user's response for this factor (and completes enrollment where applicable). */
    boolean verify(AppUser user, FactorVerificationRequest verification, HttpServletRequest request);
}
