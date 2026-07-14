package com.example.sso.logoutretry.internal;

import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * The startup guard must reject a retry store TTL shorter than the give-up horizon — otherwise a retry entry
 * could expire before it is delivered or abandoned, silently losing a logout.
 */
class RetryConfigGuardTest {

    // initial 30s, x2, +-50%, cap 10 → horizon = 45s * (2^9 - 1) = 22995s (~6.4h).
    private final RetryBackoff backoff = new RetryBackoff(Duration.ofSeconds(30), 2.0, 0.5, 10);

    @Test
    void aRegistryTtlBelowTheGiveUpHorizonFailsStartup() {
        RetryConfigGuard guard = new RetryConfigGuard(backoff, Duration.ofHours(1)); // 1h < ~6.4h horizon

        assertThatIllegalStateException().isThrownBy(guard::verify)
                .withMessageContaining("registry-ttl");
    }

    @Test
    void aRegistryTtlAboveTheGiveUpHorizonStarts() {
        RetryConfigGuard guard = new RetryConfigGuard(backoff, Duration.ofDays(8)); // the default P8D

        assertThatCode(guard::verify).doesNotThrowAnyException();
    }

    @Test
    void aRegistryTtlExactlyAtTheHorizonStarts() {
        RetryConfigGuard guard = new RetryConfigGuard(backoff, backoff.maxGiveUpHorizon());

        assertThatCode(guard::verify).doesNotThrowAnyException();
    }
}
