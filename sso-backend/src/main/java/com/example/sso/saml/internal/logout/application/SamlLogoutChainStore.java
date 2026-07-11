package com.example.sso.saml.internal.logout.application;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Short-lived state for a front-channel SLO redirect chain, keyed by a random {@code logoutId} carried in
 * RelayState across the browser hops (the OP session is already gone). Holds the remaining SPs (a Redis
 * list of entityIds, popped one per hop), the NameID per SP, and the shared SessionIndex ({@code sid}).
 */
@Service
public class SamlLogoutChainStore {

    private static final String SPS = "saml:slo:chain:%s:sps";
    private static final String NAMES = "saml:slo:chain:%s:names";
    private static final String SID = "saml:slo:chain:%s:sid";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public SamlLogoutChainStore(StringRedisTemplate redis,
            @Value("${sso.saml.slo.chain-ttl:PT5M}") Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    /** One SP hop in the chain: where to send the LogoutRequest and which subject/session it targets. */
    public record Hop(String entityId, String nameId, String sid) {
    }

    /** Persists the chain and returns its id (RelayState). Order is preserved (first pushed = first popped). */
    public String create(String logoutId, String sid, List<Participant> participants) {
        redis.opsForValue().set(SID.formatted(logoutId), sid, ttl);
        for (Participant p : participants) {
            redis.opsForList().rightPush(SPS.formatted(logoutId), p.entityId());
            redis.opsForHash().put(NAMES.formatted(logoutId), p.entityId(), p.nameId());
        }
        redis.expire(SPS.formatted(logoutId), ttl);
        redis.expire(NAMES.formatted(logoutId), ttl);
        return logoutId;
    }

    /** Pops the next SP to contact, or empty when the chain is exhausted. */
    public Optional<Hop> next(String logoutId) {
        String entityId = redis.opsForList().leftPop(SPS.formatted(logoutId));
        if (entityId == null) {
            return Optional.empty();
        }
        Object nameId = redis.opsForHash().get(NAMES.formatted(logoutId), entityId);
        String sid = redis.opsForValue().get(SID.formatted(logoutId));
        return Optional.of(new Hop(entityId, nameId == null ? null : nameId.toString(), sid));
    }

    public void clear(String logoutId) {
        redis.delete(List.of(SPS.formatted(logoutId), NAMES.formatted(logoutId), SID.formatted(logoutId)));
    }

    /** A front-channel SP participating in the chain. */
    public record Participant(String entityId, String nameId) {
    }
}
