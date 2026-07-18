package com.example.sso.mfa;

import java.util.UUID;

/**
 * A short-lived, Redis-held proof-of-ownership challenge for a user's phone number: a one-time code texted to
 * the number, redeemable once, within a TTL, and only for the exact number it was issued for. Mirrors
 * {@link EmailOwnershipProof}; the implementation stays module-internal.
 *
 * <p>The challenge is bound to the NUMBER, not just the user: if the number changes between issuing and
 * redeeming, the outstanding code is dead — a code texted to the old number must not verify a new one.
 */
public interface PhoneOwnershipProof {

    /** Issues a code for {@code phoneNumber} and texts it there (routed via {@code orgId}), replacing any
     *  outstanding challenge. */
    void challenge(UUID userId, UUID orgId, String phoneNumber);

    /**
     * Redeems {@code code} for {@code phoneNumber}. Returns false when there is no live challenge, the number
     * no longer matches, or the code is wrong — and burns the challenge once attempts are exhausted, so a
     * six-digit code cannot be brute-forced.
     */
    boolean redeem(UUID userId, String phoneNumber, String code);
}
