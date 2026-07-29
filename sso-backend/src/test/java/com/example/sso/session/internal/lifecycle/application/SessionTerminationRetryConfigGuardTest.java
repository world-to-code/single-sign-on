package com.example.sso.session.internal.lifecycle.application;

import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * The startup guard must reject a durable registry TTL shorter than the give-up horizon — otherwise a durable
 * termination entry could expire from Redis before it is delivered or abandoned, silently losing a revocation.
 *
 * <p>The schedule arithmetic itself is checked once in {@code RetryScheduleTest}; what is left here is this
 * subsystem's wiring — that the guard runs the check, and that it names this subsystem's key and noun.
 */
class SessionTerminationRetryConfigGuardTest {

    // initial 10s, x2, +-50%, cap 8 → horizon = 15s * (2^7 - 1) = 1905s (~31.75min).
    private final SessionTerminationRetryBackoff backoff =
            new SessionTerminationRetryBackoff(Duration.ofSeconds(10), 2.0, 0.5, 8);

    @Test
    void aRegistryTtlBelowTheGiveUpHorizonFailsStartup() {
        SessionTerminationRetryConfigGuard guard =
                new SessionTerminationRetryConfigGuard(backoff, Duration.ofMinutes(10)); // 10min < ~31.75min

        assertThatIllegalStateException().isThrownBy(guard::verify)
                // This subsystem's own key and noun — logoutretry passes different ones into the same shared
                // check, and a swap would point an operator at the wrong knob for the wrong loss.
                .withMessageContaining("sso.zerotrust.termination-retry.durable.registry-ttl")
                .withMessageContaining("a revocation");
    }

    @Test
    void aRegistryTtlAboveTheGiveUpHorizonStarts() {
        SessionTerminationRetryConfigGuard guard =
                new SessionTerminationRetryConfigGuard(backoff, Duration.ofHours(2)); // the default PT2H

        assertThatCode(guard::verify).doesNotThrowAnyException();
    }

    @Test
    void aRegistryTtlExactlyAtTheHorizonStarts() {
        SessionTerminationRetryConfigGuard guard =
                new SessionTerminationRetryConfigGuard(backoff, backoff.maxGiveUpHorizon());

        assertThatCode(guard::verify).doesNotThrowAnyException();
    }
}
