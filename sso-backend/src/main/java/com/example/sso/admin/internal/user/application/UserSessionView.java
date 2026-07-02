package com.example.sso.admin.internal.user.application;

import com.example.sso.session.SessionMetadata;
import java.time.Instant;

/**
 * Admin view of one of a user's active sessions. Exposes the opaque public {@code handle} (never the
 * real session id) plus the tracked device/network metadata.
 */
public record UserSessionView(String handle, String userAgent, String ip,
                              Instant createdAt, Instant lastSeenAt) {

    /** Projects a tracked session's metadata to the admin view (opaque handle only). */
    public static UserSessionView of(SessionMetadata metadata) {
        return new UserSessionView(metadata.handle(), metadata.userAgent(), metadata.ip(),
                metadata.createdAt(), metadata.lastSeenAt());
    }
}
