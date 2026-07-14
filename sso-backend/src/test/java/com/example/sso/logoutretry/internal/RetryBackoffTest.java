package com.example.sso.logoutretry.internal;

import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The backoff schedule must grow exponentially and stay inside the jitter band, so a fleet re-driving the
 * same RP outage spreads its attempts instead of retrying in lockstep, and the give-up cap is honored.
 */
class RetryBackoffTest {

    private final RetryBackoff backoff = new RetryBackoff(Duration.ofSeconds(30), 2.0, 0.5, 10);

    @Test
    void eachAttemptStaysWithinItsExponentialJitterBand() {
        // attempt N base = 30s * 2^(N-1), spread over +-50%.
        assertDelayWithin(1, 15_000, 45_000);     // base 30s
        assertDelayWithin(2, 30_000, 90_000);     // base 60s
        assertDelayWithin(3, 60_000, 180_000);    // base 120s
    }

    @Test
    void theMedianDelayGrowsPerAttempt() {
        // Sampled medians must climb even though single-sample bands overlap — the schedule is exponential.
        assertThat(median(1)).isLessThan(median(2));
        assertThat(median(2)).isLessThan(median(3));
    }

    @Test
    void exposesTheConfiguredGiveUpCap() {
        assertThat(backoff.maxAttempts()).isEqualTo(10);
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
