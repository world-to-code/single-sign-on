package com.example.sso.shared.retry;

import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The one place the retry arithmetic is checked, now that the two durable-retry subsystems share it rather
 * than each carrying a copy with a note asking the next person to keep them in step.
 */
class RetryScheduleTest {

    private final RetrySchedule schedule = RetrySchedule.of(Duration.ofSeconds(30), 2.0, 0.5, 10);

    @Test
    void eachAttemptStaysWithinItsExponentialJitterBand() {
        // attempt N base = 30s * 2^(N-1), spread over +-50%.
        assertDelayWithin(1, 15_000, 45_000);     // base 30s
        assertDelayWithin(2, 30_000, 90_000);     // base 60s
        assertDelayWithin(3, 60_000, 180_000);    // base 120s
    }

    /**
     * Asserted on sampled MEDIANS, not on two single draws: consecutive bands overlap by design, so comparing
     * one sample against the next is a coin flip that passes locally and fails in CI. That exact test was
     * written here once and had to be rewritten.
     */
    @Test
    void theMedianDelayGrowsPerAttempt() {
        assertThat(median(1)).isLessThan(median(2));
        assertThat(median(2)).isLessThan(median(3));
    }

    /** The jitter has to actually vary — a constant "random" draw would satisfy the band assertion above. */
    @Test
    void repeatedDrawsForTheSameAttemptDiffer() {
        long[] samples = new long[40];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = schedule.nextDelayMillis(3);
        }

        assertThat(Arrays.stream(samples).distinct().count()).isGreaterThan(30);
    }

    @Test
    void exposesTheConfiguredGiveUpCap() {
        assertThat(schedule.maxAttempts()).isEqualTo(10);
    }

    @Test
    void theMaxGiveUpHorizonSumsTheWorstCaseJitteredDelayOfEveryRetry() {
        // attempts 1..9: sum of 30s * 2^(k-1) * 1.5 = 45s * (2^9 - 1) = 45s * 511 = 22995s.
        assertThat(schedule.maxGiveUpHorizon()).isEqualTo(Duration.ofMillis(22_995_000L));
    }

    /** One attempt means nothing is ever RE-tried, so there is no horizon to outlive. */
    @Test
    void aScheduleWithNoRetriesHasAZeroHorizon() {
        assertThat(RetrySchedule.of(Duration.ofSeconds(30), 2.0, 0.5, 1).maxGiveUpHorizon()).isZero();
    }

    @Test
    void aStoreTtlShorterThanTheHorizonFailsClosed() {
        assertThatThrownBy(() -> schedule.requireOutlivedBy(
                schedule.maxGiveUpHorizon().minusMillis(1), "sso.example.ttl", "a logout"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sso.example.ttl")     // names the key to raise
                .hasMessageContaining("a logout");           // and what silently disappears

        assertThatCode(() -> schedule.requireOutlivedBy(
                schedule.maxGiveUpHorizon(), "sso.example.ttl", "a logout")).doesNotThrowAnyException();
    }

    private void assertDelayWithin(int attempt, long lowMillis, long highMillis) {
        for (int i = 0; i < 50; i++) {
            assertThat(schedule.nextDelayMillis(attempt)).isBetween(lowMillis, highMillis);
        }
    }

    private long median(int attempt) {
        long[] samples = new long[101];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = schedule.nextDelayMillis(attempt);
        }
        Arrays.sort(samples);
        return samples[samples.length / 2];
    }
}
