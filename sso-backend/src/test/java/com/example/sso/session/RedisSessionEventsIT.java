package com.example.sso.session;

import com.example.sso.support.AbstractIntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.springframework.session.data.redis.RedisIndexedSessionRepository.RedisSession;
import org.springframework.session.events.SessionDestroyedEvent;

import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the Spring Session (Redis) event mechanics the back-channel-logout feature relies on:
 * <ul>
 *   <li>invalidation publishes a {@code SessionDestroyedEvent} (the propagation trigger), and</li>
 *   <li><b>idle TTL expiry publishes it with NO request</b> — via Redis keyspace notifications — the only
 *       way idle-expired sessions can be caught proactively, and</li>
 *   <li>the {@code SessionMetadataCleanupListener} evicts device metadata on destroy (no leak).</li>
 * </ul>
 */
@Import(RedisSessionEventsIT.Config.class)
class RedisSessionEventsIT extends AbstractIntegrationTest {

    @Autowired
    RedisIndexedSessionRepository sessions;
    @Autowired
    CapturedEvents events;
    @Autowired
    SessionMetadataStore metadata;

    @Test
    void deletingASessionPublishesADestroyedEvent() {
        RedisSession session = sessions.createSession();
        sessions.save(session);
        String id = session.getId();

        sessions.deleteById(id);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(events.destroyed).contains(id));
    }

    @Test
    void idleTtlExpiryPublishesADestroyedEventWithoutAnyRequest() {
        RedisSession session = sessions.createSession();
        session.setMaxInactiveInterval(Duration.ofSeconds(1)); // expires ~1s after save, untouched
        sessions.save(session);
        String id = session.getId();

        // No further access — only Redis' key TTL + keyspace notification can surface this.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(events.destroyed).contains(id));
    }

    @Test
    void metadataIsEvictedWhenTheSessionIsDestroyed() {
        RedisSession session = sessions.createSession();
        sessions.save(session);
        String id = session.getId();
        metadata.record(id, "events-probe-user", "JUnit", "127.0.0.1");
        assertThat(metadata.forUser("events-probe-user")).anyMatch(m -> m.sessionId().equals(id));

        sessions.deleteById(id);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(metadata.forUser("events-probe-user")).noneMatch(m -> m.sessionId().equals(id)));
    }

    /** Captures {@code SessionDestroyedEvent} (deleted + expired) off the Redis listener thread. */
    static class CapturedEvents {
        final List<String> destroyed = new CopyOnWriteArrayList<>();

        @EventListener
        void onDestroyed(SessionDestroyedEvent event) {
            destroyed.add(event.getSessionId());
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        CapturedEvents capturedEvents() {
            return new CapturedEvents();
        }
    }
}
