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
 * nanoseconds matter. Branding is read once per page load, so that machinery buys nothing here.
 *
 * <p><b>Invalidation is a GENERATION, not a delete.</b> Every entry's key embeds a counter, and any branding
 * write increments it, retiring the whole cache at once. That is not laziness about per-tenant precision — it
 * is what makes a stale write harmless. A look-aside cache that deletes has a window a delete cannot close:
 * a reader misses, loads the pre-write rows, is descheduled while the write commits and evicts, and then
 * stores what it read, resurrecting the replaced value with no eviction left pending. Under a generation that
 * store lands under the number the reader captured, and every later read asks for the new one.
 *
 * <p>Retiring every tier on any write is also the honest reading of the data: a PLATFORM write changes what
 * every tenant resolves, because every tier inherits the platform row field by field. Branding is edited
 * rarely and a miss costs four queries, so the simpler rule is the affordable one — and it removes the
 * per-tier eviction fan-out that would otherwise have to get that inheritance right.
 *
 * <p>One key per tier, each carrying its own TTL. The TTL is a backstop for entries orphaned by a generation
 * bump, and it works only because the expiry is per key: an earlier version kept every tier in one hash and
 * expired the hash, so any tenant's fill reset the clock for everybody and the backstop could never fire.
 *
 * <p><b>Every Redis failure degrades to a miss.</b> This sits in front of the screen people sign in on;
 * failing closed there would take login down to save four queries. A read that cannot reach Redis, or finds
 * an entry it cannot parse — including one carrying a style value outside the closed set — is a miss and the
 * caller goes to the database. A write or invalidation that cannot reach Redis is logged and swallowed: the
 * database is already correct, and throwing would roll back an administrator's save over a cache.
 */
@Component
@Slf4j
class BrandingCache {

    private static final String PREFIX = "sso:branding:";
    private static final String GENERATION_KEY = PREFIX + "generation";
    /** The platform tier has no org id; a non-UUID segment cannot collide with a tenant's. */
    private static final String PLATFORM_SEGMENT = "platform";

    private final StringRedisTemplate redis;
    /**
     * Strict where it counts. Unknown PROPERTIES are tolerated so a rolling deploy can still read the previous
     * version's entry, but an unknown ENUM VALUE is not — the closed set is the whole safety story for the
     * style choices, and Redis is the one place such a value could arrive without passing the DB's CHECK.
     */
    private final ObjectMapper json = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final Duration ttl;

    BrandingCache(StringRedisTemplate redis, @Value("${sso.branding.cache-ttl-minutes:30}") long ttlMinutes) {
        this.redis = redis;
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    /**
     * The generation to read and write under. Capture it BEFORE loading from the database and pass the same
     * value to {@link #find} and {@link #put} — that pairing is what sends a store from an already-stale read
     * somewhere nothing will read it. Unreadable means zero, which is a cold cache rather than an error.
     */
    long generation() {
        try {
            String current = redis.opsForValue().get(GENERATION_KEY);
            return current == null ? 0 : Long.parseLong(current);
        } catch (RuntimeException unusable) {
            log.debug("Branding cache generation unreadable; treating the cache as cold", unusable);
            return 0;
        }
    }

    /** Retires every entry of every tier. Called AFTER COMMIT, from {@link BrandingCacheEvictor}. */
    void invalidate() {
        try {
            redis.opsForValue().increment(GENERATION_KEY);
        } catch (RuntimeException unusable) {
            log.warn("Branding cache invalidation failed; entries stay until their TTL expires them", unusable);
        }
    }

    /** The cached branding, or empty on a miss, an unreachable Redis, or an entry this version cannot read. */
    Optional<Branding> find(long generation, UUID orgId) {
        try {
            String cached = redis.opsForValue().get(key(generation, orgId));
            return cached == null ? Optional.empty() : Optional.of(json.readValue(cached, Branding.class));
        } catch (Exception unusable) {
            log.debug("Branding cache read failed; falling through to the database", unusable);
            return Optional.empty();
        }
    }

    void put(long generation, UUID orgId, Branding branding) {
        try {
            redis.opsForValue().set(key(generation, orgId), json.writeValueAsString(branding), ttl);
        } catch (Exception unusable) {
            log.debug("Branding cache write failed; the next read simply misses", unusable);
        }
    }

    private String key(long generation, UUID orgId) {
        return PREFIX + generation + ":" + (orgId == null ? PLATFORM_SEGMENT : orgId);
    }
}
