package com.example.sso.shared.retry;

import io.github.resilience4j.core.IntervalFunction;
import java.time.Duration;

/**
 * A jittered-exponential retry schedule and the give-up cap that bounds it.
 *
 * <p>Attempt N is delayed {@code initialBackoff · multiplier^(N-1)}, spread over a
 * ±{@code randomizationFactor} band. The jitter is the point, not decoration: a fleet re-driving the same
 * failing dependency must spread its attempts instead of retrying in lockstep and re-creating the stampede the
 * backoff exists to prevent. The curve itself is resilience4j's — only the SCHEDULE is ours.
 *
 * <p>Extracted when a third caller appeared, as {@code SessionTerminationRetryBackoff} said it should be. Two
 * durable-retry subsystems had been carrying identical copies of this arithmetic, each with a note asking the
 * next person to keep them in step by hand.
 */
public final class RetrySchedule {

    private final IntervalFunction interval;
    private final int maxAttempts;
    private final Duration maxGiveUpHorizon;

    private RetrySchedule(IntervalFunction interval, int maxAttempts, Duration maxGiveUpHorizon) {
        this.interval = interval;
        this.maxAttempts = maxAttempts;
        this.maxGiveUpHorizon = maxGiveUpHorizon;
    }

    public static RetrySchedule of(Duration initialBackoff, double multiplier, double randomizationFactor,
            int maxAttempts) {
        return new RetrySchedule(
                IntervalFunction.ofExponentialRandomBackoff(initialBackoff.toMillis(), multiplier,
                        randomizationFactor),
                maxAttempts,
                horizon(initialBackoff, multiplier, randomizationFactor, maxAttempts));
    }

    /** Delay before {@code attempt} (1-based: attempt 1 is the first retry, after the initial try failed). */
    public long nextDelayMillis(int attempt) {
        return interval.apply(attempt);
    }

    /** Attempts after which a still-undelivered item is abandoned. */
    public int maxAttempts() {
        return maxAttempts;
    }

    /**
     * Upper bound on the wall-clock from the first failure to give-up: the sum of the MAX-jittered delay of
     * every scheduled retry (attempts 1..maxAttempts-1).
     */
    public Duration maxGiveUpHorizon() {
        return maxGiveUpHorizon;
    }

    /**
     * Fails startup when the store holding in-flight retries would expire an entry before this schedule has
     * finished with it.
     *
     * <p>The horizon is derived from the backoff tunables while the store's TTL is set independently, so
     * raising {@code max-attempts} or the backoff without raising the TTL lets an entry vanish from Redis
     * before it is either delivered or explicitly abandoned and audited. That is a silent loss of exactly the
     * thing these subsystems exist to guarantee, so it is fail-closed rather than a warning.
     *
     * @param ttlProperty  the configuration key to name, so the message says what to raise
     * @param whatIsLost   what silently disappears — "a logout", "a revocation"
     */
    public void requireOutlivedBy(Duration storeTtl, String ttlProperty, String whatIsLost) {
        if (storeTtl.compareTo(maxGiveUpHorizon) < 0) {
            throw new IllegalStateException(ttlProperty + " (" + storeTtl + ") is shorter than the retry give-up "
                    + "horizon (" + maxGiveUpHorizon + "): an entry could expire before it is delivered or "
                    + "abandoned, silently losing " + whatIsLost + ". Raise " + ttlProperty + " above the "
                    + "horizon (or lower max-attempts / the backoff).");
        }
    }

    /** Sum of the worst-case (max-jittered) delay of every scheduled retry — attempts 1..maxAttempts-1. */
    private static Duration horizon(Duration initialBackoff, double multiplier, double randomizationFactor,
            int maxAttempts) {
        double totalMillis = 0;
        for (int attempt = 1; attempt < maxAttempts; attempt++) {
            double base = initialBackoff.toMillis() * Math.pow(multiplier, attempt - 1.0);
            totalMillis += base * (1.0 + randomizationFactor);
        }
        return Duration.ofMillis((long) Math.ceil(totalMillis));
    }
}
