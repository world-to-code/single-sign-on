package com.example.sso.logoutretry.internal;

import com.example.sso.support.AbstractIntegrationTest;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The durable retry registry against a real Redis: due-time gating, meta round-trip, the visibility-timeout
 * lease, removal, and TTL — the guarantees the scheduled sweep relies on to re-drive undelivered logouts
 * across nodes and restarts without losing or double-driving them.
 */
class DurableRetryRegistryIT extends AbstractIntegrationTest {

    @Autowired
    DurableRetryRegistry registry;
    @Autowired
    StringRedisTemplate redis;

    // A fresh queue per test so the shared singleton Redis never leaks state between cases.
    private final String queue = "test:retry:" + UUID.randomUUID();

    @Test
    void aScheduledSidIsDueOnlyAtOrAfterItsTime() {
        registry.schedule(queue, "sid-1", 1, "bob", 1_000L);

        assertThat(registry.due(queue, 999L, 10)).isEmpty();
        assertThat(registry.due(queue, 1_000L, 10)).containsExactly("sid-1");
        assertThat(registry.due(queue, 5_000L, 10)).containsExactly("sid-1");
    }

    @Test
    void metaRoundTripsAttemptsAndUsername() {
        registry.schedule(queue, "sid-1", 3, "carol", 1_000L);

        DurableRetryRegistry.Meta meta = registry.meta(queue, "sid-1").orElseThrow();
        assertThat(meta.attempts()).isEqualTo(3);
        assertThat(meta.username()).isEqualTo("carol");
    }

    @Test
    void metaIsEmptyForAnUnknownSid() {
        assertThat(registry.meta(queue, "nope")).isEmpty();
    }

    @Test
    void aNullUsernameRoundTripsAsNull() {
        registry.schedule(queue, "sid-1", 1, null, 1_000L);

        assertThat(registry.meta(queue, "sid-1").orElseThrow().username()).isNull();
    }

    @Test
    void leasingPushesTheSidOutOfTheDueWindow() {
        registry.schedule(queue, "sid-1", 1, "bob", 1_000L);
        registry.lease(queue, "sid-1", 60_000L); // a re-drive claimed it

        assertThat(registry.due(queue, 5_000L, 10)).isEmpty();      // no longer due at 5s
        assertThat(registry.due(queue, 60_000L, 10)).containsExactly("sid-1"); // re-surfaces after the lease
    }

    @Test
    void removeDropsBothTheQueueEntryAndTheMeta() {
        registry.schedule(queue, "sid-1", 1, "bob", 1_000L);

        registry.remove(queue, "sid-1");

        assertThat(registry.due(queue, 5_000L, 10)).isEmpty();
        assertThat(registry.meta(queue, "sid-1")).isEmpty();
    }

    @Test
    void scheduleAppliesATtlSoAnAbandonedEntrySelfCleans() {
        registry.schedule(queue, "sid-1", 1, "bob", 1_000L);

        assertThat(redis.getExpire(queue)).isPositive();
        assertThat(redis.getExpire(queue + ":sid-1")).isPositive();
    }

    @Test
    void reschedulingMovesTheDueTimeInPlace() {
        registry.schedule(queue, "sid-1", 1, "bob", 1_000L);
        registry.schedule(queue, "sid-1", 2, "bob", 100_000L);

        assertThat(registry.due(queue, 5_000L, 10)).isEmpty();          // old time no longer due
        assertThat(registry.due(queue, 100_000L, 10)).containsExactly("sid-1");
        assertThat(registry.meta(queue, "sid-1").orElseThrow().attempts()).isEqualTo(2);
    }

    @Test
    void theSweepLockIsHeldByExactlyOneCaller() {
        // The invariant the sweeper's single-driver guard relies on: SET NX succeeds once until it expires.
        String lock = "test:lock:" + UUID.randomUUID();
        Boolean first = redis.opsForValue().setIfAbsent(lock, "node-a", Duration.ofSeconds(25));
        Boolean second = redis.opsForValue().setIfAbsent(lock, "node-b", Duration.ofSeconds(25));

        assertThat(first).isTrue();
        assertThat(second).isFalse();
    }
}
