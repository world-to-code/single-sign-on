package com.example.sso.logoutretry.internal;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fails startup on a retry config that would silently lose logouts: the give-up horizon is derived from the
 * backoff tunables while {@code registry-ttl} is set independently, so raising one without the other lets a
 * retry entry expire from Redis before it is delivered or explicitly abandoned + audited. The comparison
 * itself lives on the shared schedule, so the session-termination guard cannot drift from this one.
 */
@Component
class RetryConfigGuard {

    private final RetryBackoff backoff;
    private final Duration registryTtl;

    RetryConfigGuard(RetryBackoff backoff,
            @Value("${sso.logout.propagation.retry.registry-ttl}") Duration registryTtl) {
        this.backoff = backoff;
        this.registryTtl = registryTtl;
    }

    @PostConstruct
    void verify() {
        backoff.requireOutlivedBy(registryTtl);
    }
}
