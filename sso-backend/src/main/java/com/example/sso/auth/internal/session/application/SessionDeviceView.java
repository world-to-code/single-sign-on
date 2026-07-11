package com.example.sso.auth.internal.session.application;

import com.example.sso.auth.internal.login.application.AuthSessionView;

import java.time.Instant;

/**
 * One of the current user's active sessions as shown on "My Profile". The {@code id} is an opaque,
 * stable handle (never the real session id), used to target a specific session for revocation. The
 * real session id is never exposed to the browser. {@code current} marks the session making the
 * request ("This device"). Named to avoid clashing with {@link AuthSessionView}.
 */
public record SessionDeviceView(String id, boolean current, String device, String userAgent, String ip,
                                Instant createdAt, Instant lastSeenAt) {
}
