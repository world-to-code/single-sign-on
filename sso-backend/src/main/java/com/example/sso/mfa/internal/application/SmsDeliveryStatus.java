package com.example.sso.mfa.internal.application;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Whether the last code sent for a given login attempt actually left the building.
 *
 * <p>The send runs off the request thread, so by the time it fails the "we texted you a code" response has
 * already reached the browser and there is nowhere left to put the bad news. Recording it here lets the next
 * request that same person makes — a resend, or a code they guessed because nothing arrived — answer honestly
 * instead of "that code is wrong".
 *
 * <p>In Redis rather than the session because the failure is written from a notification thread that holds no
 * session, and read on a later request that may land on a different node.
 *
 * <p>The entry lives as long as the code would have: past that the person's complaint is about a code that
 * expired, not one that never came.
 */
@Component
class SmsDeliveryStatus {

    private static final String KEY = "sms:delivery:failed:";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    SmsDeliveryStatus(StringRedisTemplate redis, @Value("${sso.sms-otp.ttl-minutes}") long ttlMinutes) {
        this.redis = redis;
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    void markFailed(String deliveryKey) {
        if (deliveryKey != null) {
            redis.opsForValue().set(KEY + deliveryKey, "1", ttl);
        }
    }

    boolean failed(String deliveryKey) {
        return deliveryKey != null && Boolean.TRUE.equals(redis.hasKey(KEY + deliveryKey));
    }

    /**
     * Forgets an earlier failure. Called SYNCHRONOUSLY when a fresh code is requested — if it were left to the
     * async send, a resend followed quickly by a guess would report the previous attempt's failure as this
     * one's.
     */
    void clear(String deliveryKey) {
        if (deliveryKey != null) {
            redis.delete(KEY + deliveryKey);
        }
    }
}
