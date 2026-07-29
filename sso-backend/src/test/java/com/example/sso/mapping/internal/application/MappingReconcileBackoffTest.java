package com.example.sso.mapping.internal.application;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How long a failing rule waits before the sweep tries it again.
 *
 * <p>The sweep is a fixed-interval backstop, which is right for a lost reconcile and wrong for a standing
 * conflict — the last-administrator invariant that refuses a retraction will refuse it again in ten minutes,
 * so the loop writes a refusal row 144 times a day and buries the signal it was added to give.
 */
@ExtendWith(MockitoExtension.class)
class MappingReconcileBackoffTest {

    private static final Duration INTERVAL = Duration.ofMinutes(10);
    private static final Duration MAX_DEFER = Duration.ofHours(6);
    private static final int STALLED_AFTER = 6;

    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> values;

    private MappingReconcileBackoff backoff;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(values);
        backoff = new MappingReconcileBackoff(redis, INTERVAL, 2.0, 0.5, MAX_DEFER,
                Duration.ofHours(24), STALLED_AFTER);
    }

    @Test
    void aRuleInsideItsDeferWindowIsSkipped() {
        UUID ruleId = UUID.randomUUID();
        when(redis.hasKey("mapping:reconcile:defer:" + ruleId)).thenReturn(true);

        assertThat(backoff.deferred(ruleId)).isTrue();
    }

    /**
     * Each consecutive failure buys a longer window, so a standing conflict stops costing a tick every time.
     *
     * <p>Asserted per attempt against its own jitter BAND, not by comparing two draws. The bands overlap by
     * construction — attempt 1 is 5-15 minutes and attempt 2 is 10-30 — so "the second sample exceeds the
     * first" is not a property of this function and a test that claims it is flakes roughly one run in five.
     * (It did. The full suite caught it, which is the only reason to run the whole thing before committing.)
     */
    @Test
    void eachConsecutiveFailureDoublesTheWindowItBuys() {
        UUID ruleId = UUID.randomUUID();

        List<Duration> windows = deferWindowsFor(ruleId, 1, 2, 3);

        assertThat(windows.get(0)).isBetween(jittered(INTERVAL, 0.5), jittered(INTERVAL, 1.5));
        assertThat(windows.get(1)).isBetween(jittered(INTERVAL.multipliedBy(2), 0.5),
                jittered(INTERVAL.multipliedBy(2), 1.5));
        assertThat(windows.get(2)).isBetween(jittered(INTERVAL.multipliedBy(4), 0.5),
                jittered(INTERVAL.multipliedBy(4), 1.5));
    }

    private Duration jittered(Duration base, double edge) {
        return Duration.ofMillis((long) (base.toMillis() * edge));
    }

    /**
     * The cap is where a genuine conflict comes to rest — deliberately a CAP and not a hard stop, so the rule
     * recovers by itself when an administrator resolves the conflict, with no flag for anyone to remember.
     */
    @Test
    void theWindowNeverGrowsPastTheCap() {
        UUID ruleId = UUID.randomUUID();

        assertThat(deferWindowsFor(ruleId, 20, 50, 200)).allSatisfy(window ->
                assertThat(window).isLessThanOrEqualTo(MAX_DEFER));
    }

    /**
     * Jitter, and it is the point of the whole scheme rather than a nicety: every rule that failed in ONE tick
     * would otherwise be handed the same window, so their keys expire together and the entire set lands on one
     * later tick. The failure pattern would reassemble itself into exactly the stampede the backoff prevents.
     */
    @Test
    void rulesFailingOnTheSameTickDoNotAllWakeOnTheSameOne() {
        List<Duration> windows = new ArrayList<>();
        for (int rule = 0; rule < 40; rule++) {
            windows.addAll(deferWindowsFor(UUID.randomUUID(), 3));
        }

        // "Mostly distinct" rather than "all distinct": the band is millions of milliseconds wide, so a
        // collision is possible and meaningless, while a deterministic backoff collapses all forty to ONE.
        assertThat(windows.stream().distinct().count()).isGreaterThan(30L);
    }

    @Test
    void aCleanReconcileClearsBothTheCountAndTheWindow() {
        UUID ruleId = UUID.randomUUID();

        backoff.recordSuccess(ruleId);

        verify(redis).delete("mapping:reconcile:failures:" + ruleId);
        verify(redis).delete("mapping:reconcile:defer:" + ruleId);
    }

    /** Announced ONCE, on the crossing — after that the rule is quiet, which is the whole improvement. */
    @Test
    void theStalledSignalIsRaisedOnlyOnTheCrossing() {
        UUID ruleId = UUID.randomUUID();

        assertThat(failureAt(ruleId, STALLED_AFTER - 1)).isFalse();
        assertThat(failureAt(ruleId, STALLED_AFTER)).isTrue();
        assertThat(failureAt(ruleId, STALLED_AFTER + 1)).isFalse();
    }

    private boolean failureAt(UUID ruleId, long count) {
        when(values.increment(anyString())).thenReturn(count);
        return backoff.recordFailure(ruleId);
    }

    /** The windows the backoff asks Redis to hold the defer key for, at the given consecutive-failure counts. */
    private List<Duration> deferWindowsFor(UUID ruleId, long... failureCounts) {
        List<Duration> windows = new ArrayList<>();
        for (long count : failureCounts) {
            when(values.increment(anyString())).thenReturn(count);
            backoff.recordFailure(ruleId);
            ArgumentCaptor<Duration> window = ArgumentCaptor.forClass(Duration.class);
            verify(values, atLeastOnce())
                    .set(eq("mapping:reconcile:defer:" + ruleId), any(), window.capture());
            windows.add(window.getValue());
        }
        return windows;
    }
}
