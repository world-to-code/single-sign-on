package com.example.sso.mfa;

import com.example.sso.support.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The phone proof-of-ownership challenge, against a real Redis. It is what lets a user enroll a number for
 * the SMS factor (which is only offered once the number is proven), so its edges are pinned: one live
 * challenge per user, bound to the exact number, single-use, and brute-force-bounded. Mirrors
 * {@code EmailOwnershipProofIT}.
 */
class PhoneOwnershipProofIT extends AbstractIntegrationTest {

    private static final String ALICE = "+14155550123";
    private static final String OLD = "+14155550100";
    private static final String NEW = "+14155550199";

    @Autowired
    PhoneOwnershipProof proofs;
    @Autowired
    StringRedisTemplate redis;

    private final UUID userId = UUID.randomUUID();

    /** The code Redis holds — the text body is not readable from here. */
    private String issuedCode() {
        return redis.opsForHash().get("phone:proof:" + userId, "code").toString();
    }

    @Test
    void aCodeIssuedForANumberRedeemsThatNumberOnce() {
        proofs.challenge(userId, null, ALICE);
        String code = issuedCode();

        assertThat(proofs.redeem(userId, ALICE, code)).isTrue();
        // Single use: the challenge is consumed, so a replay of the same code fails.
        assertThat(proofs.redeem(userId, ALICE, code)).isFalse();
    }

    @Test
    void aCodeDoesNotRedeemADifferentNumber() {
        // The number changed after the code was texted to the OLD line. Whoever holds that line must not be
        // able to verify the NEW number with it.
        proofs.challenge(userId, null, OLD);
        String code = issuedCode();

        assertThat(proofs.redeem(userId, NEW, code)).isFalse();
    }

    @Test
    void aWrongCodeIsRejectedAndTheChallengeBurnsAfterTooManyAttempts() {
        proofs.challenge(userId, null, ALICE);
        String code = issuedCode();

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(proofs.redeem(userId, ALICE, "000000")).isFalse();
        }
        // Exhausted: even the CORRECT code no longer works — a six-digit secret cannot be ground down.
        assertThat(proofs.redeem(userId, ALICE, code)).isFalse();
    }

    @Test
    void issuingAgainReplacesTheOutstandingChallenge() {
        proofs.challenge(userId, null, ALICE);
        String first = issuedCode();
        proofs.challenge(userId, null, ALICE);
        String second = issuedCode();

        assertThat(second).isNotEqualTo(first);
        assertThat(proofs.redeem(userId, ALICE, first)).isFalse();
        assertThat(proofs.redeem(userId, ALICE, second)).isTrue();
    }

    @Test
    void anAbandonedChallengeExpiresRatherThanLivingForever() {
        // Without a TTL an unredeemed code stays grindable and Redis grows with every request ever made.
        proofs.challenge(userId, null, ALICE);

        assertThat(redis.getExpire("phone:proof:" + userId)).isPositive();
    }

    @Test
    void redeemingWithNoLiveChallengeFails() {
        assertThat(proofs.redeem(userId, ALICE, "123456")).isFalse();
    }
}
