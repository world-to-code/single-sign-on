package com.example.sso.oidc.internal.application;

import com.example.sso.oidc.OidcBackchannelSessionIndex;
import java.time.Duration;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Redis-backed {@link OidcBackchannelSessionIndex}: per {@code sid}, a set of participating registered-client
 * internal ids ({@code oidc:bcl:{sid}:clients}) and the subject ({@code oidc:bcl:{sid}:sub}), both expiring
 * after the configured window (at least the longest possible session lifetime) so stale mappings self-clean.
 */
@Service
public class OidcBackchannelSessionIndexImpl implements OidcBackchannelSessionIndex {

    private static final String CLIENTS_KEY = "oidc:bcl:%s:clients";
    private static final String SUB_KEY = "oidc:bcl:%s:sub";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public OidcBackchannelSessionIndexImpl(StringRedisTemplate redis,
            @Value("${sso.oidc.backchannel.session-index-ttl:P8D}") Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    @Override
    public void record(String sid, String registeredClientId, String username) {
        String clients = CLIENTS_KEY.formatted(sid);
        String sub = SUB_KEY.formatted(sid);
        redis.opsForSet().add(clients, registeredClientId);
        redis.opsForValue().set(sub, username, ttl);
        redis.expire(clients, ttl);
    }

    @Override
    public Participants lookup(String sid) {
        Set<String> ids = redis.opsForSet().members(CLIENTS_KEY.formatted(sid));
        String username = redis.opsForValue().get(SUB_KEY.formatted(sid));
        return new Participants(username, ids == null ? Set.of() : ids);
    }

    @Override
    public int removeParticipants(String sid, Set<String> registeredClientIds) {
        String clients = CLIENTS_KEY.formatted(sid);
        if (!registeredClientIds.isEmpty()) {
            redis.opsForSet().remove(clients, registeredClientIds.toArray());
        }
        Long remaining = redis.opsForSet().size(clients);
        int count = remaining == null ? 0 : remaining.intValue();
        if (count == 0) {
            clear(sid); // last client settled: drop the set and the retained subject together
        }
        return count;
    }

    @Override
    public void clear(String sid) {
        redis.delete(CLIENTS_KEY.formatted(sid));
        redis.delete(SUB_KEY.formatted(sid));
    }
}
