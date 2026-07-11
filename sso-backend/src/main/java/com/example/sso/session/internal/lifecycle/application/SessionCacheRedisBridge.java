package com.example.sso.session.internal.lifecycle.application;

import com.example.sso.session.internal.networkzone.application.NetworkZoneCacheChanged;
import com.example.sso.session.internal.policy.application.SessionPolicyCacheChanged;

import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Fans out session-policy / network-zone cache invalidations across nodes. Each node holds an in-memory
 * cache rebuilt only on a LOCAL mutation; without this, other nodes would keep a STALE (possibly more
 * permissive) policy or zone until they themselves mutate or restart — a real gap for IP-rule / session
 * enforcement in a multi-node deploy.
 *
 * <p>On a local mutation (a committed transaction) the {@code *CacheChanged} event fires here and this
 * publishes to a shared Redis channel; every node (subscribed via {@link SessionCacheRedisConfig}) then
 * re-fires the SAME local event, and the cache services reload on it — their listeners use
 * {@code fallbackExecution = true}, so the re-fired (non-transactional) event still triggers the rebuild.
 *
 * <p>No loop: the outbound publishers here are plain {@code @TransactionalEventListener}s
 * ({@code fallbackExecution} defaults to false), so they run ONLY for the real-transaction local mutation —
 * never for the non-transactional event this bridge re-publishes on receipt.
 */
@Component
@RequiredArgsConstructor
public class SessionCacheRedisBridge implements MessageListener {

    static final String CHANNEL = "sso:session-cache:invalidate";
    private static final String SESSION_POLICY = "session-policy";
    private static final String NETWORK_ZONE = "network-zone";

    private final StringRedisTemplate redis;
    private final ApplicationEventPublisher events;

    @TransactionalEventListener
    void onSessionPolicyChanged(SessionPolicyCacheChanged event) {
        redis.convertAndSend(CHANNEL, SESSION_POLICY);
    }

    @TransactionalEventListener
    void onNetworkZoneChanged(NetworkZoneCacheChanged event) {
        redis.convertAndSend(CHANNEL, NETWORK_ZONE);
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        if (SESSION_POLICY.equals(body)) {
            events.publishEvent(new SessionPolicyCacheChanged());
        } else if (NETWORK_ZONE.equals(body)) {
            events.publishEvent(new NetworkZoneCacheChanged());
        }
    }
}
