package com.example.sso.ratelimit;

import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.ratelimit.internal.Bucket4jRateLimiter;
import com.example.sso.ratelimit.internal.RateLimiter;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The rate limiter, against a real Redis. Two properties matter and neither held for the in-memory
 * fixed-window limiter this replaced:
 * <ul>
 *   <li><b>Bounded burst</b> — a key may spend at most one bucket at once, never two windows' worth
 *       back-to-back across a boundary;</li>
 *   <li><b>Shared state</b> — the bucket lives in Redis, so a client cannot win a fresh allowance by landing
 *       on another node (nor by restarting ours). Proven by reaching the bucket through a SECOND limiter
 *       instance, which is what a second node is.</li>
 * </ul>
 * The refill/spend algorithm itself belongs to Bucket4j and is not re-tested here; the policy is.
 */
class RateLimiterIT extends AbstractIntegrationTest {

    @Autowired
    RateLimiter limiter;
    @Autowired
    ProxyManager<String> buckets;
    @Value("${sso.ratelimit.attempts}")
    int capacity;

    private String key() {
        return "it:" + UUID.randomUUID();
    }

    @Test
    void aFullBucketAllowsExactlyItsCapacityThenRefuses() {
        String key = key();

        for (int call = 0; call < capacity; call++) {
            assertThat(limiter.tryAcquire(key)).as("call %d", call).isTrue();
        }
        assertThat(limiter.tryAcquire(key)).isFalse();
    }

    @Test
    void bucketsAreIndependentPerKey() {
        String first = key();
        String second = key();
        for (int call = 0; call < capacity; call++) {
            limiter.tryAcquire(first);
        }

        assertThat(limiter.tryAcquire(first)).isFalse();
        assertThat(limiter.tryAcquire(second)).isTrue();
    }

    @Test
    void theBucketIsSharedAcrossNodesRatherThanPerProcess() {
        // A second limiter instance over the same Redis is what a second app node is: it must see the tokens
        // the first one already spent, not a fresh allowance.
        String key = key();
        for (int call = 0; call < capacity; call++) {
            limiter.tryAcquire(key);
        }

        // Reach the SAME Redis bucket through a fresh proxy, as another node would.
        boolean allowedOnOtherNode = buckets
                .getProxy(key, () -> buckets.getProxyConfiguration(key).orElseThrow())
                .tryConsume(1);

        assertThat(allowedOnOtherNode).isFalse();
    }

    @Test
    void anExhaustedBucketRefillsOverTime() {
        // The production window is a minute, so refilling one token would cost this test six seconds. Drive a
        // limiter with the same code and a short window instead: the property is "an empty bucket recovers",
        // not the configured rate.
        RateLimiter fast = new Bucket4jRateLimiter(buckets, 2, 1);
        String key = key();
        assertThat(fast.tryAcquire(key)).isTrue();
        assertThat(fast.tryAcquire(key)).isTrue();
        assertThat(fast.tryAcquire(key)).isFalse();

        await().atMost(Duration.ofSeconds(3)).until(() -> fast.tryAcquire(key));
    }
}
