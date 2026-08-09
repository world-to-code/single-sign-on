package com.example.sso.ratelimit.internal;

import com.example.sso.ratelimit.RateLimit;
import com.example.sso.ratelimit.RateLimits;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * The one place Bucket4j is configured. Bucket state lives in Redis (compare-and-swap, so two concurrent
 * requests can never both take the last token), which is what makes a limit hold across nodes and survive a
 * restart — in-process state silently hands a caller a fresh allowance on each one.
 */
@Component
class Bucket4jRateLimits implements RateLimits {

    private final ProxyManager<String> buckets;

    Bucket4jRateLimits(ProxyManager<String> buckets) {
        this.buckets = buckets;
    }

    @Override
    public RateLimit named(String namespace, long capacity, Duration window) {
        Supplier<BucketConfiguration> configuration = () -> BucketConfiguration.builder()
                .addLimit(Bandwidth.builder().capacity(capacity).refillGreedy(capacity, window).build())
                .build();
        return key -> buckets.getProxy(namespace + ":" + key, configuration).tryConsume(1);
    }
}
