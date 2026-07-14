package com.example.sso.oidc.internal.application;

import com.example.sso.logoutretry.LogoutRetryDriver;
import org.springframework.stereotype.Component;

/**
 * Bridges the generic logout-retry sweep to OIDC back-channel logout. A separate bean from
 * {@link LogoutPropagationImpl} on purpose: {@code redrive} calls the {@link LogoutPropagation} interface
 * (a proxy), so the {@code @Async} on {@code propagate} still applies — invoking it from within the impl
 * would be a self-invocation and run synchronously on the sweeper thread.
 */
@Component
class OidcLogoutRetryDriver implements LogoutRetryDriver {

    // Disjoint from the session-index namespace (oidc:bcl:{sid}:*) so a retry key can never collide with an index key.
    static final String RETRY_QUEUE = "logout:retry:oidc";

    private final LogoutPropagation propagation;

    OidcLogoutRetryDriver(LogoutPropagation propagation) {
        this.propagation = propagation;
    }

    @Override
    public String queue() {
        return RETRY_QUEUE;
    }

    @Override
    public void redrive(String sid, String username) {
        propagation.propagate(sid, username);
    }
}
