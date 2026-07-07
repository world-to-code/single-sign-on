package com.example.sso.session.internal.application;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SessionCacheRedisBridge}: a local cache change is broadcast to the Redis channel,
 * and an inbound message re-fires the matching local {@code *CacheChanged} event so this node rebuilds too.
 *
 * <p>NOTE — the anti-loop invariant is NOT exercised here. It rests on the outbound publishers being plain
 * {@code @TransactionalEventListener} (default {@code fallbackExecution = false}), so the non-transactional
 * event this bridge re-fires on receipt does NOT trigger another Redis publish. These unit tests call the
 * listener methods directly, which bypasses that annotation semantics — so they would still pass if the
 * annotation regressed to {@code @EventListener} (an actual infinite fan-out). That property is enforced
 * structurally by the annotation and must be verified in a full-context/integration run, not asserted here.
 */
@ExtendWith(MockitoExtension.class)
class SessionCacheRedisBridgeTest {

    @Mock private StringRedisTemplate redis;
    @Mock private ApplicationEventPublisher events;

    @InjectMocks private SessionCacheRedisBridge bridge;

    private Message message(String body) {
        Message message = mock(Message.class);
        when(message.getBody()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        return message;
    }

    @Test
    void aLocalSessionPolicyChangeIsBroadcastToTheChannel() {
        bridge.onSessionPolicyChanged(new SessionPolicyCacheChanged());
        verify(redis).convertAndSend(SessionCacheRedisBridge.CHANNEL, "session-policy");
    }

    @Test
    void aLocalNetworkZoneChangeIsBroadcastToTheChannel() {
        bridge.onNetworkZoneChanged(new NetworkZoneCacheChanged());
        verify(redis).convertAndSend(SessionCacheRedisBridge.CHANNEL, "network-zone");
    }

    @Test
    void anInboundSessionPolicyMessageRefiresTheLocalReloadEvent() {
        bridge.onMessage(message("session-policy"), null);
        verify(events).publishEvent(new SessionPolicyCacheChanged());
    }

    @Test
    void anInboundNetworkZoneMessageRefiresTheLocalReloadEvent() {
        bridge.onMessage(message("network-zone"), null);
        verify(events).publishEvent(new NetworkZoneCacheChanged());
    }

    @Test
    void anUnknownMessageIsIgnored() {
        bridge.onMessage(message("something-else"), null);
        verify(events, never()).publishEvent(any());
    }
}
