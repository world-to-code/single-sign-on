package com.example.sso.logoutretry.internal;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fails startup on a retry config that would silently lose logouts. The give-up horizon (total time from the
 * first failure to abandonment, {@link RetryBackoff#maxGiveUpHorizon()}) is derived from the backoff tunables
 * and the cap, while the retry store TTL is set independently — so raising {@code max-attempts} or the backoff
 * without raising {@code registry-ttl} could let a retry entry EXPIRE from Redis before it is delivered or
 * explicitly abandoned+audited, dropping the logout with no give-up record. This guard makes that
 * misconfiguration loud (fail-closed) instead of a silent revocation gap.
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
        Duration horizon = backoff.maxGiveUpHorizon();
        if (registryTtl.compareTo(horizon) < 0) {
            throw new IllegalStateException(
                    "sso.logout.propagation.retry.registry-ttl (" + registryTtl + ") is shorter than the retry "
                            + "give-up horizon (" + horizon + "): a retry entry could expire before it is "
                            + "delivered or abandoned, silently losing a logout. Raise registry-ttl above the "
                            + "horizon (or lower max-attempts / the backoff).");
        }
    }
}
