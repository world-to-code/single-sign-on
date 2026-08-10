package com.example.sso.audit.internal.application;

import com.example.sso.audit.export.AuditExportHealth;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * How the export is actually doing — kept in Redis, because the export is ONE logical thing spread over a
 * fleet.
 *
 * <p>The failure streak used to be a field on the sweeper. Only the node that wins the lock attempts a
 * delivery, so on three nodes each one saw roughly a third of the failures and the give-up alarm took three
 * times as long to fire; a rolling restart set every count back to zero, which a deployment does exactly when
 * an operator is most likely to be watching. The cursor is in Redis for this same reason and the counter
 * contradicted it.
 *
 * <p>The last-success stamp exists because the failure streak cannot answer the question that matters. A
 * collector accepting batches steadily while falling further behind produces no failures at all — the honest
 * signal is how old the newest exported event is, which is what {@link AuditExportHealth} carries.
 */
@Component
@RequiredArgsConstructor
class AuditExportStatus {

    private static final String FAILURES_KEY = "sso:audit:export:failures";
    private static final String LAST_SUCCESS_KEY = "sso:audit:export:last-success";

    /**
     * Long enough that an operator arriving the next morning still sees when it last worked, short enough that
     * a decommissioned export does not leave a stamp behind for ever.
     */
    private static final Duration LAST_SUCCESS_RETENTION = Duration.ofDays(30);

    private final StringRedisTemplate redis;
    private final AuditExportReader reader;

    /** @return the new consecutive-failure count across the whole fleet. */
    long recordFailure() {
        Long failures = redis.opsForValue().increment(FAILURES_KEY);
        return failures == null ? 1 : failures;
    }

    /** A delivery landed: the streak is over, and this is the moment to measure staleness against. */
    void recordSuccess() {
        redis.opsForValue().set(LAST_SUCCESS_KEY, String.valueOf(Instant.now().toEpochMilli()),
                LAST_SUCCESS_RETENTION);
        redis.delete(FAILURES_KEY);
    }

    /** Clears the streak without claiming a delivery — used once the give-up has been recorded. */
    void clearFailures() {
        redis.delete(FAILURES_KEY);
    }

    AuditExportHealth health() {
        Instant position = reader.position().orElse(null);
        return new AuditExportHealth(lastSuccessAt(), position, behindBySeconds(position), failures());
    }

    /**
     * How stale the collector's copy is, in seconds — the number an operator actually needs, computed here
     * because a browser comparing its own clock to a server timestamp measures clock skew as well as lag.
     */
    private Long behindBySeconds(Instant position) {
        return position == null ? null : Math.max(0, Duration.between(position, Instant.now()).toSeconds());
    }

    private Instant lastSuccessAt() {
        String stamped = redis.opsForValue().get(LAST_SUCCESS_KEY);
        return stamped == null ? null : Instant.ofEpochMilli(Long.parseLong(stamped));
    }

    private long failures() {
        String counted = redis.opsForValue().get(FAILURES_KEY);
        return counted == null ? 0 : Long.parseLong(counted);
    }
}
