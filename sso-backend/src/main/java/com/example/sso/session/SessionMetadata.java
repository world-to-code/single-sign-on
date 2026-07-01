package com.example.sso.session;

import java.time.Instant;

/**
 * Per-session device/activity metadata shown to the owner on the self-service "My Profile" page.
 * Immutable except for {@code lastSeenAt}, which is refreshed (volatile, no setter — only the
 * intention-revealing {@link #touch}) on every authenticated request by the
 * {@link com.example.sso.security.SessionIntegrityFilter}.
 */
public final class SessionMetadata {

    /**
     * Stable, opaque public identifier for this session, decoupled from the real session id. It is
     * what the self-service API exposes (and accepts for revocation) so the actual session id never
     * leaves the server. Preserved across {@code changeSessionId()} rotation (see the store's rekey).
     */
    private final String handle;
    private final String sessionId;
    private final String username;
    private final String userAgent;
    private final String ip;
    private final Instant createdAt;
    private volatile Instant lastSeenAt;

    public SessionMetadata(String handle, String sessionId, String username, String userAgent, String ip,
                           Instant createdAt) {
        this.handle = handle;
        this.sessionId = sessionId;
        this.username = username;
        this.userAgent = userAgent;
        this.ip = ip;
        this.createdAt = createdAt;
        this.lastSeenAt = createdAt;
    }

    /** Refreshes the activity stamp (called per authenticated request). */
    public void touch(Instant when) {
        this.lastSeenAt = when;
    }

    public String handle() {
        return handle;
    }

    public String sessionId() {
        return sessionId;
    }

    public String username() {
        return username;
    }

    public String userAgent() {
        return userAgent;
    }

    public String ip() {
        return ip;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant lastSeenAt() {
        return lastSeenAt;
    }
}
