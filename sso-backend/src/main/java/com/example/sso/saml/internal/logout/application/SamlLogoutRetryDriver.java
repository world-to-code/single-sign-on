package com.example.sso.saml.internal.logout.application;

import com.example.sso.logoutretry.LogoutRetryDriver;
import org.springframework.stereotype.Component;

/**
 * Bridges the generic logout-retry sweep to SAML Single Logout. A separate bean from
 * {@link SamlLogoutPropagationImpl} on purpose: {@code redrive} calls the {@link SamlLogoutPropagation}
 * interface (a proxy), so the {@code @Async} on {@code propagate} still applies — invoking it from within
 * the impl would be a self-invocation and run synchronously on the sweeper thread.
 */
@Component
class SamlLogoutRetryDriver implements LogoutRetryDriver {

    // Disjoint from the session-index namespace (saml:slo:{sid}) so a retry key can never collide with an index key.
    static final String RETRY_QUEUE = "logout:retry:saml";

    private final SamlLogoutPropagation propagation;

    SamlLogoutRetryDriver(SamlLogoutPropagation propagation) {
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
