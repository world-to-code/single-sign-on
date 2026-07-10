package com.example.sso.ratelimit.internal;

import com.example.sso.ratelimit.RateLimiter;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@link RateLimiter} backed by Bucket4j over Redis. The bucket state lives in Redis (compare-and-swap, so
 * two concurrent requests can never both take the last token), which is what makes the limit hold across
 * nodes and survive a restart — in-process state silently hands a client a fresh allowance on each.
 *
 * <p>The refill-and-spend algorithm belongs to the library; this class only names the policy: a bucket of
 * {@code sso.ratelimit.attempts} tokens (the bounded burst) refilling greedily over
 * {@code sso.ratelimit.window-seconds}.
 */
@Component
public class Bucket4jRateLimiter implements RateLimiter {

    private final ProxyManager<String> buckets;
    private final Supplier<BucketConfiguration> configuration;

    public Bucket4jRateLimiter(ProxyManager<String> buckets,
            @Value("${sso.ratelimit.attempts}") long attempts,
            @Value("${sso.ratelimit.window-seconds}") long windowSeconds) {
        this.buckets = buckets;
        Duration window = Duration.ofSeconds(windowSeconds);
        this.configuration = () -> BucketConfiguration.builder()
                .addLimit(Bandwidth.builder().capacity(attempts).refillGreedy(attempts, window).build())
                .build();
    }

    @Override
    public boolean tryAcquire(String key) {
        return buckets.getProxy(key, configuration).tryConsume(1);
    }
}
