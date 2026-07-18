package com.example.sso.mfa;

import com.example.sso.support.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The email proof-of-ownership challenge, against a real Redis. It is what lets a user re-prove an address an
 * admin changed (which clears the verified flag and disables the EMAIL factor), so its edges are pinned:
 * one live challenge per user, bound to the exact address, single-use, and brute-force-bounded.
 */
class EmailOwnershipProofIT extends AbstractIntegrationTest {

    @Autowired
    EmailOwnershipProof proofs;
    @Autowired
    StringRedisTemplate redis;

    private final UUID userId = UUID.randomUUID();

    /** The code Redis holds — the mail body is not readable from here. */
    private String issuedCode() {
        return redis.opsForHash().get("email:proof:" + userId, "code").toString();
    }

    @Test
    void aCodeIssuedForAnAddressRedeemsThatAddressOnce() {
        proofs.challenge(userId, null, "alice@example.com");
        String code = issuedCode();

        assertThat(proofs.redeem(userId, "alice@example.com", code)).isTrue();
        // Single use: the challenge is consumed, so a replay of the same code fails.
        assertThat(proofs.redeem(userId, "alice@example.com", code)).isFalse();
    }

    @Test
    void aCodeDoesNotRedeemADifferentAddress() {
        // The admin changed the address after the code was mailed to the OLD mailbox. Whoever holds that
        // mailbox must not be able to verify the NEW address with it.
        proofs.challenge(userId, null, "old@example.com");
        String code = issuedCode();

        assertThat(proofs.redeem(userId, "new@example.com", code)).isFalse();
    }

    @Test
    void aWrongCodeIsRejectedAndTheChallengeBurnsAfterTooManyAttempts() {
        proofs.challenge(userId, null, "alice@example.com");
        String code = issuedCode();

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(proofs.redeem(userId, "alice@example.com", "000000")).isFalse();
        }
        // Exhausted: even the CORRECT code no longer works — a six-digit secret cannot be ground down.
        assertThat(proofs.redeem(userId, "alice@example.com", code)).isFalse();
    }

    @Test
    void issuingAgainReplacesTheOutstandingChallenge() {
        proofs.challenge(userId, null, "alice@example.com");
        String first = issuedCode();
        proofs.challenge(userId, null, "alice@example.com");
        String second = issuedCode();

        assertThat(second).isNotEqualTo(first);
        assertThat(proofs.redeem(userId, "alice@example.com", first)).isFalse();
        assertThat(proofs.redeem(userId, "alice@example.com", second)).isTrue();
    }

    @Test
    void anAbandonedChallengeExpiresRatherThanLivingForever() {
        // Without a TTL an unredeemed code stays grindable and Redis grows with every request ever made.
        proofs.challenge(userId, null, "alice@example.com");

        assertThat(redis.getExpire("email:proof:" + userId)).isPositive();
    }

    @Test
    void redeemingWithNoLiveChallengeFails() {
        assertThat(proofs.redeem(userId, "alice@example.com", "123456")).isFalse();
    }
}
