package com.example.sso.session;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory (single-node) store of device/activity metadata per HTTP session, keyed by session id.
 * Mirrors how the {@link org.springframework.security.core.session.SessionRegistry} is used: the
 * SessionRegistry tracks liveness for concurrent-session control, while this store remembers the
 * client (User-Agent, IP, timestamps) so the owner can review and revoke their own sessions on the
 * "My Profile" page. Entries are added when a session reaches {@code MFA_COMPLETE}, refreshed per
 * authenticated request, and removed on session destruction.
 */
@Component
public class SessionMetadataStore {

    private final Map<String, SessionMetadata> sessions = new ConcurrentHashMap<>();

    /** Records (or replaces) the metadata for a freshly completed session, minting a fresh public handle. */
    public void record(String sessionId, String username, String userAgent, String ip) {
        String handle = UUID.randomUUID().toString();
        sessions.put(sessionId, new SessionMetadata(handle, sessionId, username, userAgent, ip, Instant.now()));
    }

    /** Refreshes the last-seen stamp for an existing session (no-op if unknown). */
    public void touch(String sessionId) {
        SessionMetadata metadata = sessions.get(sessionId);
        if (metadata != null) {
            metadata.touch(Instant.now());
        }
    }

    /** Forgets a session (on logout / expiry / invalidation). */
    public void remove(String sessionId) {
        sessions.remove(sessionId);
    }

    /**
     * Re-keys an entry after {@code HttpServletRequest.changeSessionId()} (step-up rotation), preserving
     * the original device info + creation time. Without this the rotated session would vanish from the
     * owner's list and the old-id entry would leak (changeSessionId fires no sessionDestroyed event).
     */
    public void rekey(String oldId, String newId) {
        SessionMetadata old = sessions.remove(oldId);
        if (old == null || oldId.equals(newId)) {
            return;
        }

        // Carry the original public handle so the rotated session keeps the same identity in the UI.
        SessionMetadata moved = new SessionMetadata(old.handle(), newId, old.username(), old.userAgent(),
                old.ip(), old.createdAt());
        moved.touch(old.lastSeenAt());
        sessions.put(newId, moved);
    }

    /** All tracked sessions belonging to the given user, newest first. */
    public List<SessionMetadata> forUser(String username) {
        return sessions.values().stream()
                .filter(m -> m.username().equals(username))
                .sorted((a, b) -> b.createdAt().compareTo(a.createdAt()))
                .toList();
    }

    /** Resolves one of the user's OWN sessions by its public handle (scoped to the user, so handles never collide cross-user). */
    public Optional<SessionMetadata> findByUserAndHandle(String username, String handle) {
        return sessions.values().stream()
                .filter(m -> m.username().equals(username) && m.handle().equals(handle))
                .findFirst();
    }
}
