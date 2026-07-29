package com.example.sso.session;

import com.example.sso.session.lifecycle.SessionMetadata;
import com.example.sso.session.lifecycle.SessionMetadataStore;
import com.example.sso.support.AbstractIntegrationTest;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.springframework.session.data.redis.RedisIndexedSessionRepository.RedisSession;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Session device metadata across a FLEET, which is the property that matters and the one a single-node test
 * cannot see.
 *
 * <p>This store used to be a {@code ConcurrentHashMap} field. Behind a load balancer that meant a user's
 * session list showed only the sessions whose login happened to land on the node serving the request, and
 * revoking one of the others answered "not found" — a person could not end the session on a device they had
 * lost. The fix is that the SESSION IS THE STORE: the device is an attribute of the Redis session, so every
 * node reads the same answer and nothing has to be replicated or invalidated.
 *
 * <p>A second {@link SessionMetadataStore} instance over the same repository stands in for a second node. It
 * is a fair stand-in precisely because the store holds no state of its own any more — if it did, this test
 * would be the thing that noticed.
 */
class SessionMetadataStoreIT extends AbstractIntegrationTest {

    private static final String USER = "fleet-probe-user";

    @Autowired RedisIndexedSessionRepository sessions;
    @Autowired SessionMetadataStore thisNode;
    @Autowired FindByIndexNameSessionRepository<? extends Session> repository;

    /** A store instance that shares nothing with the autowired one but the Redis behind it. */
    private SessionMetadataStore anotherNode() {
        return new SessionMetadataStore(repository);
    }

    @Test
    void aSessionRecordedOnOneNodeIsListedByAnother() {
        String sessionId = signIn("Firefox on Linux", "203.0.113.7");

        List<SessionMetadata> asSeenElsewhere = anotherNode().forUser(USER);

        assertThat(asSeenElsewhere).extracting(SessionMetadata::sessionId).contains(sessionId);
        SessionMetadata seen = asSeenElsewhere.stream()
                .filter(metadata -> metadata.sessionId().equals(sessionId)).findFirst().orElseThrow();
        assertThat(seen.userAgent()).isEqualTo("Firefox on Linux");
        assertThat(seen.ip()).isEqualTo("203.0.113.7");
    }

    /**
     * The handle is what revocation accepts, so it has to mean the same thing on every node. A per-node handle
     * would make "end this session" reach a different session — or none — depending on which node answered.
     */
    @Test
    void theHandleIsTheSameOnEveryNodeAndResolvesBackToTheSession() {
        String sessionId = signIn("Safari", "198.51.100.4");
        String handle = handleOf(thisNode.forUser(USER), sessionId);

        assertThat(handleOf(anotherNode().forUser(USER), sessionId)).isEqualTo(handle);

        Optional<SessionMetadata> resolved = anotherNode().findByUserAndHandle(USER, handle);
        assertThat(resolved).get().extracting(SessionMetadata::sessionId).isEqualTo(sessionId);
    }

    /** Another user's handle must not resolve, whichever node is asked — the lookup is scoped to the owner. */
    @Test
    void aHandleDoesNotResolveForADifferentUser() {
        String sessionId = signIn("Chrome", "192.0.2.9");
        String handle = handleOf(thisNode.forUser(USER), sessionId);

        assertThat(anotherNode().findByUserAndHandle("someone-else", handle)).isEmpty();
    }

    /**
     * A handle must select ONE of the user's sessions — the right one. Revocation resolves the target this way,
     * so a lookup that merely picked the user's first session would end whichever session happened to sort
     * first rather than the one the person chose to end.
     */
    @Test
    void aHandleResolvesToItsOwnSessionAndNotTheUsersOther() {
        String phone = signIn("Chrome on Android", "192.0.2.10");
        String laptop = signIn("Firefox on Linux", "192.0.2.11");
        String phoneHandle = handleOf(thisNode.forUser(USER), phone);
        String laptopHandle = handleOf(thisNode.forUser(USER), laptop);

        assertThat(phoneHandle).isNotEqualTo(laptopHandle);
        assertThat(anotherNode().findByUserAndHandle(USER, phoneHandle))
                .get().extracting(SessionMetadata::sessionId).isEqualTo(phone);
        assertThat(anotherNode().findByUserAndHandle(USER, laptopHandle))
                .get().extracting(SessionMetadata::sessionId).isEqualTo(laptop);
    }

    /**
     * Deleting the session takes the metadata with it. There is no eviction listener to get this right any
     * more — the attribute cannot outlive its session, which is the point of storing it there.
     */
    @Test
    void deletingTheSessionRemovesItsMetadataWithNoEvictionStep() {
        String sessionId = signIn("Edge", "203.0.113.8");

        sessions.deleteById(sessionId);

        assertThat(anotherNode().forUser(USER))
                .noneMatch(metadata -> metadata.sessionId().equals(sessionId));
    }

    /**
     * Step-up rotates the session id. The handle has to survive it, or the session the user was looking at
     * silently becomes a different one in the list — and the handle they might already hold stops resolving.
     * Spring Session carries attributes across the rotation; this is the test that says so out loud.
     */
    @Test
    void theHandleSurvivesASessionIdRotation() {
        String sessionId = signIn("Firefox", "203.0.113.10");
        String before = handleOf(thisNode.forUser(USER), sessionId);

        RedisSession session = sessions.findById(sessionId);
        session.changeSessionId();
        sessions.save(session);
        String rotated = session.getId();

        assertThat(rotated).isNotEqualTo(sessionId);
        assertThat(handleOf(anotherNode().forUser(USER), rotated)).isEqualTo(before);
    }

    /** A session that never completed sign-in carries no device, and must not appear as a phantom entry. */
    @Test
    void aSessionWithNoRecordedDeviceIsNotListed() {
        RedisSession session = sessions.createSession();
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, USER);
        sessions.save(session);

        assertThat(anotherNode().forUser(USER))
                .noneMatch(metadata -> metadata.sessionId().equals(session.getId()));
    }

    /**
     * Creates a signed-in session the way the login path does — through the servlet session, which is how the
     * store is actually called — and returns its id.
     */
    private String signIn(String userAgent, String ip) {
        RedisSession backing = sessions.createSession();
        backing.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, USER);
        backing.setMaxInactiveInterval(Duration.ofMinutes(30));
        HttpSession session = new RedisBackedHttpSession(backing);

        thisNode.record(session, userAgent, ip);

        sessions.save(backing);
        return backing.getId();
    }

    private String handleOf(List<SessionMetadata> metadata, String sessionId) {
        return metadata.stream()
                .filter(entry -> entry.sessionId().equals(sessionId))
                .map(SessionMetadata::handle)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no metadata for session " + sessionId));
    }
}
