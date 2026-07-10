package com.example.sso.mfa.internal.application;

import com.example.sso.mfa.EmailOwnershipProof;
import com.example.sso.mfa.EmailVerificationService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Redis-backed {@link EmailOwnershipProof}: one hash per user ({@code email:proof:{userId}}) holding the
 * outstanding code, the address it was issued for, and the remaining attempts. Redis owns the lifetime — the
 * key carries the TTL, so an abandoned challenge expires itself and survives an app restart or a request
 * landing on another node (unlike an HttpSession-scoped code).
 *
 * <p>Issuing again overwrites the hash, so a user always has exactly ONE live challenge. Redeeming deletes
 * it (single use), a wrong code decrements the attempts, and exhausting them deletes it too — a six-digit
 * secret is otherwise grindable within its TTL.
 */
@Service
public class EmailOwnershipProofImpl implements EmailOwnershipProof {

    private static final String KEY = "email:proof:%s";
    private static final String CODE = "code";
    private static final String EMAIL = "email";
    private static final String ATTEMPTS = "attempts";

    private final StringRedisTemplate redis;
    private final EmailVerificationService emails;
    private final Duration ttl;
    private final int maxAttempts;

    public EmailOwnershipProofImpl(StringRedisTemplate redis, EmailVerificationService emails,
            @Value("${sso.email-otp.ttl-minutes}") long ttlMinutes,
            @Value("${sso.email-otp.max-attempts}") int maxAttempts) {
        this.redis = redis;
        this.emails = emails;
        this.ttl = Duration.ofMinutes(ttlMinutes);
        this.maxAttempts = maxAttempts;
    }

    @Override
    public void challenge(UUID userId, String email) {
        String key = KEY.formatted(userId);
        String code = emails.generateCode();
        redis.delete(key); // exactly one live challenge per user
        redis.opsForHash().putAll(key, Map.of(
                CODE, code, EMAIL, email, ATTEMPTS, String.valueOf(maxAttempts)));
        redis.expire(key, ttl);
        emails.sendCode(email, code);
    }

    @Override
    public boolean redeem(UUID userId, String email, String code) {
        String key = KEY.formatted(userId);
        Object issuedFor = redis.opsForHash().get(key, EMAIL);
        Object expected = redis.opsForHash().get(key, CODE);
        if (issuedFor == null || expected == null || code == null) {
            return false;
        }
        // The challenge proves control of the address it was MAILED to. If the address moved on since, the
        // code in the old mailbox must not verify the new one.
        if (!issuedFor.toString().equals(email) || !matches(expected.toString(), code)) {
            burnOnExhaustion(key);
            return false;
        }

        redis.delete(key); // single use
        return true;
    }

    /** Constant-time compare so a near-miss code cannot be distinguished by response timing. */
    private boolean matches(String expected, String presented) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    private void burnOnExhaustion(String key) {
        Long remaining = redis.opsForHash().increment(key, ATTEMPTS, -1);
        if (remaining != null && remaining <= 0) {
            redis.delete(key);
        }
    }
}
