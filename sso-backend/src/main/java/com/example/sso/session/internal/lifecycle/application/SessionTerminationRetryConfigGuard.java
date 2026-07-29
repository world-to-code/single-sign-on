package com.example.sso.session.internal.lifecycle.application;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fails startup on a durable-retry config that would silently lose a termination: the give-up horizon is
 * derived from the backoff tunables while {@code registry-ttl} is set independently, so raising one without the
 * other lets a durable entry expire from Redis before it is delivered or explicitly abandoned + audited. The
 * comparison itself lives on the shared schedule, so this and {@code logoutretry}'s guard can no longer drift.
 */
@Component
class SessionTerminationRetryConfigGuard {

    private final SessionTerminationRetryBackoff backoff;
    private final Duration registryTtl;

    SessionTerminationRetryConfigGuard(SessionTerminationRetryBackoff backoff,
            @Value("${sso.zerotrust.termination-retry.durable.registry-ttl}") Duration registryTtl) {
        this.backoff = backoff;
        this.registryTtl = registryTtl;
    }

    @PostConstruct
    void verify() {
        backoff.requireOutlivedBy(registryTtl);
    }
}
