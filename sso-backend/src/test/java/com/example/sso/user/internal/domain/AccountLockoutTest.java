package com.example.sso.user.internal.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for the immutable {@link AccountLockout} value object: counting consecutive failures and
 * flipping into a temporary lock once the threshold is reached. Pure domain rule — asserts on state.
 */
class AccountLockoutTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration LOCK_FOR = Duration.ofMinutes(15);

    @Test
    void noneStartsUnlockedWithZeroFailures() {
        AccountLockout none = AccountLockout.none();

        assertThat(none.failedAttempts()).isZero();
        assertThat(none.lockedUntil()).isNull();
        assertThat(none.isTemporarilyLocked(NOW)).isFalse();
    }

    @Test
    void failuresBelowThresholdCountButDoNotLock() {
        AccountLockout after = AccountLockout.none()
                .registerFailure(3, LOCK_FOR, NOW)
                .registerFailure(3, LOCK_FOR, NOW);

        assertThat(after.failedAttempts()).isEqualTo(2);
        assertThat(after.lockedUntil()).isNull();
        assertThat(after.isTemporarilyLocked(NOW)).isFalse();
    }

    @Test
    void reachingThresholdLocksUntilNowPlusDuration() {
        AccountLockout locked = AccountLockout.none()
                .registerFailure(2, LOCK_FOR, NOW)
                .registerFailure(2, LOCK_FOR, NOW);

        assertThat(locked.failedAttempts()).isEqualTo(2);
        assertThat(locked.lockedUntil()).isEqualTo(NOW.plus(LOCK_FOR));
        assertThat(locked.isTemporarilyLocked(NOW)).isTrue();
    }

    @Test
    void lockExpiresOnceNowIsPastTheDeadline() {
        AccountLockout locked = AccountLockout.none()
                .registerFailure(1, LOCK_FOR, NOW);

        assertThat(locked.isTemporarilyLocked(NOW.plus(LOCK_FOR))).isFalse();
        assertThat(locked.isTemporarilyLocked(NOW.plus(LOCK_FOR).plusSeconds(1))).isFalse();
    }
}
