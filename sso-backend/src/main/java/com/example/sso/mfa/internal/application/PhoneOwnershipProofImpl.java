package com.example.sso.mfa.internal.application;

import com.example.sso.mfa.PhoneOwnershipProof;
import com.example.sso.mfa.SmsVerificationService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Redis-backed {@link PhoneOwnershipProof}: one hash per user ({@code phone:proof:{userId}}) holding the
 * outstanding code, the number it was issued for, and the remaining attempts, with the TTL on the key. Exactly
 * one live challenge per user (re-issue overwrites); single-use (redeem deletes); a wrong code decrements the
 * attempts and exhausting them deletes the key — a six-digit secret is otherwise grindable within its TTL.
 * A clone of {@code EmailOwnershipProofImpl}.
 */
@Service
public class PhoneOwnershipProofImpl implements PhoneOwnershipProof {

    private static final String KEY = "phone:proof:%s";
    private static final String CODE = "code";
    private static final String PHONE = "phone";
    private static final String ATTEMPTS = "attempts";

    private final StringRedisTemplate redis;
    private final SmsVerificationService sms;
    private final Duration ttl;
    private final int maxAttempts;

    public PhoneOwnershipProofImpl(StringRedisTemplate redis, SmsVerificationService sms,
            @Value("${sso.sms-otp.ttl-minutes}") long ttlMinutes,
            @Value("${sso.sms-otp.max-attempts}") int maxAttempts) {
        this.redis = redis;
        this.sms = sms;
        this.ttl = Duration.ofMinutes(ttlMinutes);
        this.maxAttempts = maxAttempts;
    }

    @Override
    public void challenge(UUID userId, UUID orgId, String phoneNumber) {
        String key = KEY.formatted(userId);
        String code = sms.generateCode();
        redis.delete(key); // exactly one live challenge per user
        redis.opsForHash().putAll(key, Map.of(
                CODE, code, PHONE, phoneNumber, ATTEMPTS, String.valueOf(maxAttempts)));
        redis.expire(key, ttl);
        sms.sendCode(orgId, phoneNumber, code);
    }

    @Override
    public boolean redeem(UUID userId, String phoneNumber, String code) {
        String key = KEY.formatted(userId);
        Object issuedFor = redis.opsForHash().get(key, PHONE);
        Object expected = redis.opsForHash().get(key, CODE);
        if (issuedFor == null || expected == null || code == null) {
            return false;
        }
        // The challenge proves control of the number it was TEXTED to. If the number moved on since, the code
        // sent to the old number must not verify the new one.
        if (!issuedFor.toString().equals(phoneNumber) || !matches(expected.toString(), code)) {
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
