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
    private static final String RESPONDER = "saml:slo:chain:%s:responder";
    private static final String RESPONDER_ENTITY = "entityId";
    private static final String RESPONDER_REQUEST = "requestId";
    private static final String RESPONDER_RELAY = "relayState";

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

    /** The SP-initiated logout's originator, answered with a {@code LogoutResponse} once the chain drains. */
    public record Responder(String entityId, String requestId, String relayState) {
    }

    /**
     * Persists the chain and returns its id (RelayState). Order is preserved (first pushed = first popped).
     * {@code responder} is the SP-initiated originator to answer once the chain drains, or null for the
     * explicit-browser-logout path (which just lands on the post-logout page when the chain drains).
     */
    public String create(String logoutId, String sid, List<Participant> participants, Responder responder) {
        redis.opsForValue().set(SID.formatted(logoutId), sid, ttl);
        for (Participant p : participants) {
            redis.opsForList().rightPush(SPS.formatted(logoutId), p.entityId());
            redis.opsForHash().put(NAMES.formatted(logoutId), p.entityId(), p.nameId());
        }
        redis.expire(SPS.formatted(logoutId), ttl);
        redis.expire(NAMES.formatted(logoutId), ttl);
        if (responder != null) {
            String key = RESPONDER.formatted(logoutId);
            redis.opsForHash().put(key, RESPONDER_ENTITY, responder.entityId());
            redis.opsForHash().put(key, RESPONDER_REQUEST, responder.requestId());
            if (responder.relayState() != null) {
                redis.opsForHash().put(key, RESPONDER_RELAY, responder.relayState());
            }
            redis.expire(key, ttl);
        }
        return logoutId;
    }

    /** The SP-initiated originator to answer, if this chain was staged from an inbound {@code LogoutRequest}. */
    public Optional<Responder> responder(String logoutId) {
        String key = RESPONDER.formatted(logoutId);
        Object entityId = redis.opsForHash().get(key, RESPONDER_ENTITY);
        if (entityId == null) {
            return Optional.empty();
        }
        Object requestId = redis.opsForHash().get(key, RESPONDER_REQUEST);
        Object relayState = redis.opsForHash().get(key, RESPONDER_RELAY);
        return Optional.of(new Responder(entityId.toString(),
                requestId == null ? null : requestId.toString(),
                relayState == null ? null : relayState.toString()));
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
        redis.delete(List.of(SPS.formatted(logoutId), NAMES.formatted(logoutId), SID.formatted(logoutId),
                RESPONDER.formatted(logoutId)));
    }

    /** A front-channel SP participating in the chain. */
    public record Participant(String entityId, String nameId) {
    }
}
