package com.example.sso.session.internal.lifecycle.application;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fails startup on a durable-retry config that would silently lose a termination. The give-up horizon (total
 * time from hand-off to abandonment, {@link SessionTerminationRetryBackoff#maxGiveUpHorizon()}) is derived from
 * the backoff tunables and the cap, while the registry TTL is set independently — so raising the backoff or the
 * cap without raising {@code registry-ttl} could let a durable entry EXPIRE from Redis before it is delivered or
 * explicitly abandoned + audited, dropping the termination with no give-up record. This guard makes that
 * misconfiguration loud (fail-closed) rather than a silent revocation gap.
 *
 * <p>Mirrors {@code logoutretry}'s {@code RetryConfigGuard}; kept a deliberate clone under the rule of three
 * (see {@link SessionTerminationRetryBackoff}) — a fix to this fail-closed check must be mirrored there.
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
        Duration horizon = backoff.maxGiveUpHorizon();
        if (registryTtl.compareTo(horizon) < 0) {
            throw new IllegalStateException(
                    "sso.zerotrust.termination-retry.durable.registry-ttl (" + registryTtl + ") is shorter than "
                            + "the give-up horizon (" + horizon + "): a durable termination entry could expire "
                            + "before it is delivered or abandoned, silently losing a revocation. Raise "
                            + "registry-ttl above the horizon (or lower max-attempts / the backoff).");
        }
    }
}
