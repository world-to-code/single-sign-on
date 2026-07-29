package com.example.sso.mapping.internal.application;

import io.github.resilience4j.core.IntervalFunction;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * How often the sweep re-drives a rule that keeps failing.
 *
 * <p>The sweep is a fixed-interval backstop, so it re-drives every rule every tick regardless of whether the
 * last attempt could ever have worked. For a rule in a PERSISTENT conflict that is not a retry, it is a loop:
 * the last-administrator invariant refuses the retraction, the transaction rolls back, and ten minutes later
 * exactly the same thing happens. The refusal audit row — added so an operator could SEE a rule that had
 * stopped converging — then arrives 144 times a day and buries the signal it exists to give.
 *
 * <p>So consecutive failures push the rule out exponentially, up to a cap. A transient failure clears on the
 * next success and costs one delayed tick; a genuine conflict settles at the cap, which is the "quarantine" —
 * announced once ({@code MAPPING_RULE_RECONCILE_STALLED}) and then quiet.
 *
 * <p>Deliberately a CAP rather than a hard stop. A rule parked at six hours needs no manual un-quarantine flow
 * and recovers by itself the moment an administrator resolves the conflict; a hard stop would need somebody to
 * notice and clear a flag, which is one more thing to forget.
 *
 * <p>State lives in Redis, not in the node: the sweep's single-driver lock means consecutive ticks can be
 * driven by DIFFERENT nodes, so an in-memory counter would reset whenever the driver changed.
 *
 * <p>JITTERED, and that is not decoration. Every rule that fails in one tick would otherwise be handed the
 * SAME defer window, so their keys expire together and the whole set lands on one later tick — the failure
 * pattern reassembles itself into a stampede, and a tick that re-drives fifty stalled rules at once is exactly
 * what the backoff was meant to prevent. The interval comes from the same resilience4j {@link IntervalFunction}
 * the sibling retry uses ({@code ReconcileRetry}), so the two spread their load the same way.
 */
@Component
class MappingReconcileBackoff {

    private static final String DEFER_KEY = "mapping:reconcile:defer:";
    private static final String FAILURES_KEY = "mapping:reconcile:failures:";

    private final StringRedisTemplate redis;
    private final IntervalFunction deferInterval;
    private final Duration failureWindow;
    private final int stalledAfter;

    MappingReconcileBackoff(StringRedisTemplate redis,
            @Value("${sso.mapping.reconcile.sweep.interval}") Duration interval,
            @Value("${sso.mapping.reconcile.sweep.backoff.multiplier}") double multiplier,
            @Value("${sso.mapping.reconcile.sweep.backoff.randomization-factor}") double randomizationFactor,
            @Value("${sso.mapping.reconcile.sweep.backoff.max-defer}") Duration maxDefer,
            @Value("${sso.mapping.reconcile.sweep.backoff.failure-window}") Duration failureWindow,
            @Value("${sso.mapping.reconcile.sweep.backoff.stalled-after}") int stalledAfter) {
        this.redis = redis;
        this.deferInterval = IntervalFunction.ofExponentialRandomBackoff(
                interval, multiplier, randomizationFactor, maxDefer);
        this.failureWindow = failureWindow;
        this.stalledAfter = stalledAfter;
    }

    /** Whether this rule is still inside the window its last failure bought. */
    boolean deferred(UUID ruleId) {
        return Boolean.TRUE.equals(redis.hasKey(DEFER_KEY + ruleId));
    }

    /**
     * Records a failed reconcile and defers the rule for the next window. Returns true the ONCE the rule
     * crosses into stalled — so the caller announces it exactly once rather than on every failure.
     */
    boolean recordFailure(UUID ruleId) {
        Long failures = redis.opsForValue().increment(FAILURES_KEY + ruleId);
        long count = failures == null ? 1L : failures;
        redis.expire(FAILURES_KEY + ruleId, failureWindow);
        redis.opsForValue().set(DEFER_KEY + ruleId, Long.toString(count), deferFor(count));
        return count == stalledAfter;
    }

    /** A rule that reconciled cleanly starts again from zero — including one that had been stalled. */
    void recordSuccess(UUID ruleId) {
        redis.delete(FAILURES_KEY + ruleId);
        redis.delete(DEFER_KEY + ruleId);
    }

    /**
     * interval x multiplier^(failures-1), jittered, and capped at {@code max-defer} BY THE INTERVAL FUNCTION —
     * the cap is not re-implemented here, because two mechanisms enforcing one rule is how they drift apart.
     * The only clamp is on the cast: the count is bounded by its own TTL long before this could matter, but a
     * long that wrapped negative would ask for a tiny interval, which is a fail-open the type system invites.
     */
    private Duration deferFor(long failures) {
        return Duration.ofMillis(deferInterval.apply((int) Math.min(failures, Integer.MAX_VALUE)));
    }
}
