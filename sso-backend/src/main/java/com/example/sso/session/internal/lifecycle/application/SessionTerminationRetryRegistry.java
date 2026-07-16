package com.example.sso.session.internal.lifecycle.application;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * The durable, distributed backing store for session-termination retries. Two Redis structures: a ZSET (member
 * = the request key, score = the epoch-millis it is next due) that the sweep range-queries, and a small meta
 * Hash per member holding the attempt count and the fields to reconstruct the {@link SessionTerminationRequest}
 * so a later sweep (possibly on another node, after a restart) can re-drive it and, on give-up, audit. Both
 * carry a TTL so an abandoned entry self-cleans (the config guard keeps that TTL above the give-up horizon).
 */
@Component
class SessionTerminationRetryRegistry {

    private static final String QUEUE = "session:termination:retry";
    private static final String META_KEY = QUEUE + ":%s";
    private static final String FIELD_ATTEMPTS = "attempts";
    private static final String FIELD_ORG = "org";
    private static final String FIELD_USERNAME = "username";
    private static final String FIELD_USER_ID = "userId";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    SessionTerminationRetryRegistry(StringRedisTemplate redis,
            @Value("${sso.zerotrust.termination-retry.durable.registry-ttl}") Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    /** A persisted, due-to-be-driven termination: the request to re-drive and how many attempts it has had. */
    record Pending(SessionTerminationRequest request, int attempts) {
    }

    /** Enqueue (or move) {@code request} to fire at {@code dueAtEpochMillis}, stamping the attempt count. */
    void schedule(SessionTerminationRequest request, int attempts, long dueAtEpochMillis) {
        String key = request.key();
        String meta = META_KEY.formatted(key);
        redis.opsForZSet().add(QUEUE, key, dueAtEpochMillis);
        redis.opsForHash().put(meta, FIELD_ATTEMPTS, Integer.toString(attempts));
        redis.opsForHash().put(meta, FIELD_ORG, request.orgId() == null ? "" : request.orgId().toString());
        redis.opsForHash().put(meta, FIELD_USERNAME, request.username() == null ? "" : request.username());
        redis.opsForHash().put(meta, FIELD_USER_ID, request.userId() == null ? "" : request.userId().toString());
        redis.expire(QUEUE, ttl);
        redis.expire(meta, ttl);
    }

    /** Up to {@code limit} request keys whose next-retry time is at or before {@code now} (soonest first). */
    List<String> due(long nowEpochMillis, int limit) {
        Set<String> due = redis.opsForZSet().rangeByScore(QUEUE, 0, nowEpochMillis, 0, limit);
        return due == null ? List.of() : List.copyOf(due);
    }

    /** The pending state for {@code key}, or empty if it was never scheduled (or already settled/expired). */
    Optional<Pending> pending(String key) {
        String meta = META_KEY.formatted(key);
        Object attempts = redis.opsForHash().get(meta, FIELD_ATTEMPTS);
        if (attempts == null) {
            return Optional.empty();
        }
        UUID orgId = parseUuid(redis.opsForHash().get(meta, FIELD_ORG));
        String username = emptyToNull(redis.opsForHash().get(meta, FIELD_USERNAME));
        UUID userId = parseUuid(redis.opsForHash().get(meta, FIELD_USER_ID));
        SessionTerminationRequest request = new SessionTerminationRequest(orgId, username, userId);
        return Optional.of(new Pending(request, Integer.parseInt(attempts.toString())));
    }

    /**
     * Push {@code key}'s next-due time out to {@code untilEpochMillis} so a concurrent or crashing sweep does not
     * re-pick it while a re-drive is in flight (a visibility timeout): if the driver dies, it re-surfaces later.
     */
    void lease(String key, long untilEpochMillis) {
        redis.opsForZSet().add(QUEUE, key, untilEpochMillis);
    }

    /** Remove {@code key} from the queue and drop its meta — it was delivered or abandoned. */
    void remove(String key) {
        redis.opsForZSet().remove(QUEUE, key);
        redis.delete(META_KEY.formatted(key));
    }

    private UUID parseUuid(Object value) {
        String text = emptyToNull(value);
        return text == null ? null : UUID.fromString(text);
    }

    private String emptyToNull(Object value) {
        return value == null || value.toString().isEmpty() ? null : value.toString();
    }
}
