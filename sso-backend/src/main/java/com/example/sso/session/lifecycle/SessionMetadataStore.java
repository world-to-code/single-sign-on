package com.example.sso.session.lifecycle;

import jakarta.servlet.http.HttpSession;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

/**
 * Device/activity metadata per session — which client signed in, from where, when, and the opaque handle the
 * owner uses to revoke it.
 *
 * <p><b>The session IS the store.</b> The device is an attribute of the Redis session, so any node answers the
 * same way and nothing has to be replicated, invalidated or cleaned up. It used to be a {@code ConcurrentHashMap}
 * field, which behind a load balancer meant a user saw only the sessions whose login had landed on the node
 * serving the request — and revoking any of the others answered "not found", so a person could not end the
 * session on a device they had lost.
 *
 * <p>Writes go through the caller's own {@link HttpSession} rather than a separately-loaded copy. That is not a
 * convenience: a session id rotation rewrites the session from the instance the request holds, so an attribute
 * written onto a second instance would be dropped by the next {@code changeSessionId()}.
 *
 * <p>Complements Spring's {@code SessionRegistry}, which tracks liveness for concurrent-session control.
 *
 * <p>Sessions established before this existed carry no device and are simply not listed; the caller's own is
 * backfilled on sight, and the rest age out with their TTL. Nothing to migrate.
 */
@Component
@RequiredArgsConstructor
public class SessionMetadataStore {

    /** Namespaced so it cannot collide with a framework attribute on the same session. */
    private static final String DEVICE_ATTRIBUTE = "com.example.sso.session.DEVICE";

    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    /**
     * Stamps the client onto the session, minting a fresh public handle. Called when a session reaches
     * {@code MFA_COMPLETE}, and to backfill a session that predates its own metadata.
     */
    public void record(HttpSession session, String userAgent, String ip) {
        session.setAttribute(DEVICE_ATTRIBUTE,
                new SessionDevice(UUID.randomUUID().toString(), userAgent, ip));
    }

    /** All of the user's sessions that carry recorded device metadata, newest first. */
    public List<SessionMetadata> forUser(String username) {
        return sessions.findByPrincipalName(username).entrySet().stream()
                .map(entry -> metadataOf(entry.getKey(), username, entry.getValue()))
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(SessionMetadata::createdAt).reversed())
                .toList();
    }

    /** One of the user's OWN sessions by its public handle — scoped to the user, so handles never collide. */
    public Optional<SessionMetadata> findByUserAndHandle(String username, String handle) {
        return forUser(username).stream()
                .filter(metadata -> metadata.handle().equals(handle))
                .findFirst();
    }

    /**
     * Projects one session, or empty when it carries no device — a session that has not completed sign-in is
     * not something to show as a phantom entry.
     */
    private Optional<SessionMetadata> metadataOf(String sessionId, String username, Session session) {
        SessionDevice device = session.getAttribute(DEVICE_ATTRIBUTE);
        if (device == null) {
            return Optional.empty();
        }
        return Optional.of(new SessionMetadata(device.handle(), sessionId, username, device.userAgent(),
                device.ip(), session.getCreationTime(), session.getLastAccessedTime()));
    }
}
