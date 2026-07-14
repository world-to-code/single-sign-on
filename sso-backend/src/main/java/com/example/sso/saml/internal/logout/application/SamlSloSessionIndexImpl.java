package com.example.sso.saml.internal.logout.application;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Redis-backed {@link SamlSloSessionIndex}: a Hash {@code saml:slo:{sid}} mapping each participant SP's
 * entityId to the NameID it received, expiring after the configured window so stale mappings self-clean.
 * Mirrors {@code OidcBackchannelSessionIndexImpl}.
 */
@Service
public class SamlSloSessionIndexImpl implements SamlSloSessionIndex {

    private static final String KEY = "saml:slo:%s";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public SamlSloSessionIndexImpl(StringRedisTemplate redis,
            @Value("${sso.saml.slo.session-index-ttl:P8D}") Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    @Override
    public void record(String sid, String entityId, String nameId) {
        String key = KEY.formatted(sid);
        redis.opsForHash().put(key, entityId, nameId);
        redis.expire(key, ttl);
    }

    @Override
    public Map<String, String> lookup(String sid) {
        Map<Object, Object> entries = redis.opsForHash().entries(KEY.formatted(sid));
        Map<String, String> result = new LinkedHashMap<>();
        entries.forEach((k, v) -> result.put(k.toString(), v.toString()));
        return result;
    }

    @Override
    public int removeParticipants(String sid, Set<String> entityIds) {
        String key = KEY.formatted(sid);
        if (!entityIds.isEmpty()) {
            redis.opsForHash().delete(key, entityIds.toArray());
        }
        Long remaining = redis.opsForHash().size(key);
        return remaining == null ? 0 : remaining.intValue();
    }

    @Override
    public void clear(String sid) {
        redis.delete(KEY.formatted(sid));
    }
}
