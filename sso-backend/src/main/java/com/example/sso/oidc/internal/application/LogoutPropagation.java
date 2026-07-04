package com.example.sso.oidc.internal.application;

/**
 * Sends OIDC back-channel {@code logout_token}s to every client that participated in a terminated OP
 * session (looked up by {@code sid}). Invoked from the session-termination listener, so it covers logout,
 * idle/absolute expiry, and concurrent eviction uniformly.
 */
interface LogoutPropagation {

    void propagate(String sid, String username);
}
