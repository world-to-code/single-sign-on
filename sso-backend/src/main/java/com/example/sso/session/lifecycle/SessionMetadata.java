package com.example.sso.session.lifecycle;

import java.time.Instant;

/**
 * Per-session device/activity metadata shown to the owner on the self-service "My Profile" page, and to an
 * administrator on the user detail screen.
 *
 * <p>A read model assembled from the session itself: only {@code handle}, {@code userAgent} and {@code ip} are
 * stored ({@link SessionDevice}); {@code createdAt} and {@code lastSeenAt} come from the session's own creation
 * and last-accessed stamps, which Spring Session already maintains on every request. That is why there is no
 * {@code touch} any more — the activity stamp was being written a second time, in a node-local map, to say
 * something the session had already recorded.
 *
 * @param handle stable, opaque public identifier, decoupled from the real session id: it is what the
 *               self-service API exposes and accepts for revocation, so the session id never leaves the server.
 */
public record SessionMetadata(String handle, String sessionId, String username, String userAgent, String ip,
                              Instant createdAt, Instant lastSeenAt) {
}
