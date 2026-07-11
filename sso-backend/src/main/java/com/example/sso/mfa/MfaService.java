package com.example.sso.mfa;

import com.example.sso.user.account.UserAccount;

import java.util.UUID;

/**
 * MFA module's public contract for TOTP enrollment and verification. The implementation and the
 * backing {@code TotpService} stay module-internal.
 */
public interface MfaService {

    /**
     * Generates a fresh TOTP secret and {@code otpauth://} URI for enrollment. The secret is NOT
     * persisted here — the caller holds it in the HTTP session until {@link #confirmEnrollment}
     * verifies a code.
     */
    TotpEnrollment newEnrollment(UserAccount user);

    /** Rebuilds the enrollment (otpauth URI) for an existing, session-held pending secret. */
    TotpEnrollment enrollmentFor(UserAccount user, String secret);

    /**
     * Confirms enrollment by verifying a code against the pending (session-held) secret, then upserts
     * the user's single TOTP factor and enables it.
     */
    boolean confirmEnrollment(UserAccount user, String secret, String code);

    /** Verifies a TOTP code at challenge time against the user's enabled factor, rejecting replays. */
    boolean verifyTotp(UUID userId, String code);

    boolean hasEnabledTotp(UUID userId);

    /** Removes all MFA factors for a user so they must re-enroll (admin recovery). */
    void resetMfa(UUID userId);
}
