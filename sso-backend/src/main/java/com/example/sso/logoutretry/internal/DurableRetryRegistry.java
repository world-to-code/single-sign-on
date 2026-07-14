package com.example.sso.logoutretry.internal;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * The durable, distributed backing store for logout-propagation retries — one queue per protocol, keyed by
 * the queue name the driver owns. Two Redis structures per queue: a ZSET (member = {@code sid}, score = the
 * epoch-millis the sid is next due for a re-drive) that the sweeper range-queries, and a small meta Hash per
 * sid holding the attempt count and subject so a later sweep (possibly on another node, after a restart) can
 * re-drive and, on give-up, audit. Both carry a TTL so an abandoned entry self-cleans.
 */
@Component
class DurableRetryRegistry {

    private static final String META_KEY = "%s:%s";
    private static final String FIELD_ATTEMPTS = "attempts";
    private static final String FIELD_USERNAME = "username";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    DurableRetryRegistry(StringRedisTemplate redis,
            @Value("${sso.logout.propagation.retry.registry-ttl}") Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    /** The persisted retry state for one sid: how many attempts have been made, and the subject to re-drive as. */
    record Meta(int attempts, String username) {
    }

    /** Enqueue (or move) {@code sid} to fire at {@code dueAtEpochMillis}, stamping the attempt count + subject. */
    void schedule(String queue, String sid, int attempts, String username, long dueAtEpochMillis) {
        String meta = META_KEY.formatted(queue, sid);
        redis.opsForZSet().add(queue, sid, dueAtEpochMillis);
        redis.opsForHash().put(meta, FIELD_ATTEMPTS, Integer.toString(attempts));
        redis.opsForHash().put(meta, FIELD_USERNAME, username == null ? "" : username);
        redis.expire(queue, ttl);
        redis.expire(meta, ttl);
    }

    /** The retry state for {@code sid}, or empty if it was never scheduled (or already settled/expired). */
    Optional<Meta> meta(String queue, String sid) {
        String key = META_KEY.formatted(queue, sid);
        Object attempts = redis.opsForHash().get(key, FIELD_ATTEMPTS);
        if (attempts == null) {
            return Optional.empty();
        }
        Object username = redis.opsForHash().get(key, FIELD_USERNAME);
        String subject = username == null || username.toString().isEmpty() ? null : username.toString();
        return Optional.of(new Meta(Integer.parseInt(attempts.toString()), subject));
    }

    /** Up to {@code limit} sids whose next-retry time is at or before {@code nowEpochMillis} (soonest first). */
    List<String> due(String queue, long nowEpochMillis, int limit) {
        Set<String> due = redis.opsForZSet().rangeByScore(queue, 0, nowEpochMillis, 0, limit);
        return due == null ? List.of() : List.copyOf(due);
    }

    /**
     * Push {@code sid}'s next-due time out to {@code untilEpochMillis} so a concurrent or crashing sweep does not
     * re-pick it while a re-drive is in flight (a visibility timeout): if the driver dies, it re-surfaces later.
     */
    void lease(String queue, String sid, long untilEpochMillis) {
        redis.opsForZSet().add(queue, sid, untilEpochMillis);
    }

    /** Remove {@code sid} from the queue and drop its meta — it was delivered or abandoned. */
    void remove(String queue, String sid) {
        redis.opsForZSet().remove(queue, sid);
        redis.delete(META_KEY.formatted(queue, sid));
    }
}
