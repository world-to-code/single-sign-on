package com.example.sso.admin.internal.application;

import java.time.Instant;

/**
 * Admin view of one of a user's active sessions. Exposes the opaque public {@code handle} (never the
 * real session id) plus the tracked device/network metadata.
 */
public record UserSessionView(String handle, String userAgent, String ip,
                              Instant createdAt, Instant lastSeenAt) {
}
