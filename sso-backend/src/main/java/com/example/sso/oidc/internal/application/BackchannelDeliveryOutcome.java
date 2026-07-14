package com.example.sso.oidc.internal.application;

/**
 * Outcome of one client's back-channel {@code logout_token} attempt, driving clear-only-delivered:
 * {@code DELIVERED} and {@code TERMINAL} (retrying can never succeed — the client is gone or was never a
 * back-channel-logout client) are settled and removed from the index; {@code TRANSIENT} (the endpoint failed
 * to answer, or the token could not be built right now) is kept for the durable retry sweep.
 */
enum BackchannelDeliveryOutcome {
    DELIVERED,
    TERMINAL,
    TRANSIENT
}
