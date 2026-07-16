package com.example.sso.session.internal.lifecycle.application;

import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The durable backoff must grow exponentially inside its jitter band (so a fleet re-driving the same store
 * recovery spreads its attempts instead of retrying in lockstep) and expose the give-up cap and horizon the
 * sweep and the config guard rely on.
 */
class SessionTerminationRetryBackoffTest {

    // initial 10s, x2, +-50%, cap 8.
    private final SessionTerminationRetryBackoff backoff =
            new SessionTerminationRetryBackoff(Duration.ofSeconds(10), 2.0, 0.5, 8);

    @Test
    void eachAttemptStaysWithinItsExponentialJitterBand() {
        assertDelayWithin(1, 5_000, 15_000);   // base 10s
        assertDelayWithin(2, 10_000, 30_000);  // base 20s
        assertDelayWithin(3, 20_000, 60_000);  // base 40s
    }

    @Test
    void theMedianDelayGrowsPerAttempt() {
        assertThat(median(1)).isLessThan(median(2));
        assertThat(median(2)).isLessThan(median(3));
    }

    @Test
    void exposesTheConfiguredGiveUpCap() {
        assertThat(backoff.maxAttempts()).isEqualTo(8);
    }

    @Test
    void theMaxGiveUpHorizonSumsTheWorstCaseJitteredDelayOfEveryRetry() {
        // attempts 1..7: sum of 10s * 2^(k-1) * 1.5 = 15s * (2^7 - 1) = 15s * 127 = 1905s.
        assertThat(backoff.maxGiveUpHorizon()).isEqualTo(Duration.ofMillis(1_905_000L));
    }

    private void assertDelayWithin(int attempt, long lowMillis, long highMillis) {
        for (int i = 0; i < 50; i++) {
            assertThat(backoff.nextDelayMillis(attempt)).isBetween(lowMillis, highMillis);
        }
    }

    private long median(int attempt) {
        long[] samples = new long[101];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = backoff.nextDelayMillis(attempt);
        }
        Arrays.sort(samples);
        return samples[samples.length / 2];
    }
}
