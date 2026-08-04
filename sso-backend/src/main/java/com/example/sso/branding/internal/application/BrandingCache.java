package com.example.sso.branding.internal.application;

import com.example.sso.branding.Branding;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * A shared cache for resolved {@link Branding}, in front of the PUBLIC sign-in endpoint.
 *
 * <p>Resolving branding costs four queries — the tenant's row and the platform's, for both the theme and the
 * screen wording — on an endpoint that is unauthenticated and hit on every page load. This IdP is a single
 * point of failure for every application behind it, so an unauthenticated read with a per-request database
 * fan-out is worth removing.
 *
 * <p><b>Why Redis and not an in-process cache.</b> The session-policy and network-zone caches in this
 * codebase are in-process with a Redis pub/sub fan-out, because they are consulted on EVERY request and the
 * nanoseconds matter. Branding is read once per page load, so that machinery buys nothing here and costs a
 * staleness window between publishing an invalidation and every node receiving it. A shared entry has no such
 * window: deleting it is immediately true for the whole fleet.
 *
 * <p>One Redis HASH holds every tier, keyed by org id, so both eviction shapes are a single command: drop one
 * field for a tenant, drop the key for the platform. A TTL rides on the hash purely as a backstop — eviction
 * is explicit, and the TTL only bounds how long a missed one could last.
 *
 * <p><b>Every Redis failure degrades to a miss.</b> This sits in front of the screen people sign in on;
 * failing closed there would take login down to save four queries. A read that cannot reach Redis, or finds
 * an entry it cannot parse, is a miss and the caller goes to the database. A write or eviction that cannot
 * reach Redis is logged and swallowed — the database is already correct, and throwing would roll back the
 * administrator's save over a cache.
 */
@Component
@Slf4j
class BrandingCache {

    private static final String KEY = "sso:branding";
    /** The platform tier has no org id; a non-UUID field name cannot collide with a tenant's. */
    private static final String PLATFORM_FIELD = "platform";

    private final StringRedisTemplate redis;
    /**
     * Lenient on unknown properties: during a rolling deploy the entry in Redis may have been written by the
     * previous version. Tolerating a field this version does not know beats discarding the whole entry, and a
     * value it genuinely cannot read still falls through to the database.
     */
    private final ObjectMapper json = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final Duration ttl;

    BrandingCache(StringRedisTemplate redis, @Value("${sso.branding.cache-ttl-minutes:30}") long ttlMinutes) {
        this.redis = redis;
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    /** The cached branding for this tier, or empty on a miss, an unreachable Redis, or an unreadable entry. */
    Optional<Branding> find(UUID orgId) {
        try {
            Object cached = redis.opsForHash().get(KEY, field(orgId));
            return cached == null ? Optional.empty() : Optional.of(json.readValue(cached.toString(), Branding.class));
        } catch (Exception unusable) {
            log.debug("Branding cache read failed; falling through to the database", unusable);
            return Optional.empty();
        }
    }

    void put(UUID orgId, Branding branding) {
        try {
            redis.opsForHash().put(KEY, field(orgId), json.writeValueAsString(branding));
            redis.expire(KEY, ttl);
        } catch (Exception unusable) {
            log.debug("Branding cache write failed; the next read simply misses", unusable);
        }
    }

    /**
     * Drops what a write to {@code writtenOrg} invalidated. A {@code null} org is a PLATFORM write, and that
     * evicts EVERY tenant: every tier inherits the platform row field by field, so a platform edit changes
     * what every tenant resolves. Evicting only the written tier would leave the fleet serving values the
     * platform no longer has.
     */
    void evictWrittenBy(UUID writtenOrg) {
        try {
            if (writtenOrg == null) {
                redis.delete(KEY);
            } else {
                redis.opsForHash().delete(KEY, field(writtenOrg));
            }
        } catch (Exception unusable) {
            log.warn("Branding cache eviction failed; entries stay until the TTL expires them", unusable);
        }
    }

    private String field(UUID orgId) {
        return orgId == null ? PLATFORM_FIELD : orgId.toString();
    }
}
