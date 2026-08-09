package com.example.sso.ratelimit.internal;

import com.example.sso.ratelimit.RateLimit;
import com.example.sso.ratelimit.RateLimits;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@link RateLimiter} for the authentication endpoints: a bucket of {@code sso.ratelimit.attempts} tokens
 * refilling over {@code sso.ratelimit.window-seconds}.
 *
 * <p>This class only names the policy. The bucket itself comes from {@link RateLimits}, so the login limit
 * and the response API's budget are the same mechanism with different numbers rather than two configurations
 * of the same library that can drift apart.
 */
@Component
public class Bucket4jRateLimiter implements RateLimiter {

    private static final String NAMESPACE = "auth";

    private final RateLimit limit;

    public Bucket4jRateLimiter(RateLimits rateLimits,
            @Value("${sso.ratelimit.attempts}") long attempts,
            @Value("${sso.ratelimit.window-seconds}") long windowSeconds) {
        this.limit = rateLimits.named(NAMESPACE, attempts, Duration.ofSeconds(windowSeconds));
    }

    @Override
    public boolean tryAcquire(String key) {
        return limit.tryAcquire(key);
    }
}
