package com.example.sso.mfa;

import java.util.UUID;

/**
 * A short-lived, Redis-held proof-of-ownership challenge for a user's email address: a one-time code sent to
 * the address, redeemable once, within a TTL, and only for the exact address it was issued for. The
 * implementation stays module-internal.
 *
 * <p>The challenge is bound to the ADDRESS, not just the user: if the address changes between issuing and
 * redeeming (an admin edit), the outstanding code is dead — otherwise a code mailed to the old mailbox would
 * verify the new one.
 */
public interface EmailOwnershipProof {

    /**
     * Issues a code for {@code email} and mails it there (via {@code orgId}'s SMTP relay, or the platform
     * default when null), replacing any outstanding challenge.
     */
    void challenge(UUID userId, UUID orgId, String email);

    /**
     * Redeems {@code code} for {@code email}. Returns false when there is no live challenge, the address no
     * longer matches, or the code is wrong — and burns the challenge once the attempts are exhausted, so a
     * six-digit code cannot be brute-forced.
     */
    boolean redeem(UUID userId, String email, String code);
}
