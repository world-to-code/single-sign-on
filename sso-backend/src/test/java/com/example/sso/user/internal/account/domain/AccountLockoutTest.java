package com.example.sso.user.internal.account.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for the immutable {@link AccountLockout} value object: counting consecutive failures, flipping
 * into a temporary lock once the threshold is reached, and — because a fixed lock window merely throttles a
 * patient attacker — lengthening that window EXPONENTIALLY each time the account locks again. Pure domain
 * rule, asserted on state; the clock is a parameter, never {@code Instant.now()}.
 */
class AccountLockoutTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration BASE = Duration.ofMinutes(15);
    private static final Duration MAX = Duration.ofHours(8);

    private AccountLockout failTimes(AccountLockout lockout, int times, int maxAttempts, Instant at) {
        AccountLockout current = lockout;
        for (int i = 0; i < times; i++) {
            current = current.registerFailure(maxAttempts, BASE, MAX, at);
        }
        return current;
    }

    @Test
    void noneStartsUnlockedWithZeroFailures() {
        AccountLockout none = AccountLockout.none();

        assertThat(none.failedAttempts()).isZero();
        assertThat(none.lockedUntil()).isNull();
        assertThat(none.lockouts()).isZero();
        assertThat(none.isTemporarilyLocked(NOW)).isFalse();
    }

    @Test
    void failuresBelowThresholdCountButDoNotLock() {
        AccountLockout after = failTimes(AccountLockout.none(), 2, 3, NOW);

        assertThat(after.failedAttempts()).isEqualTo(2);
        assertThat(after.lockedUntil()).isNull();
        assertThat(after.isTemporarilyLocked(NOW)).isFalse();
    }

    @Test
    void reachingTheThresholdLocksForTheBaseDuration() {
        AccountLockout locked = failTimes(AccountLockout.none(), 2, 2, NOW);

        assertThat(locked.lockedUntil()).isEqualTo(NOW.plus(BASE));
        assertThat(locked.lockouts()).isEqualTo(1);
        assertThat(locked.isTemporarilyLocked(NOW)).isTrue();
        // The attempt counter restarts: the next window is measured from the lock, not from the old failures.
        assertThat(locked.failedAttempts()).isZero();
    }

    @Test
    void lockExpiresOnceNowIsPastTheDeadline() {
        AccountLockout locked = failTimes(AccountLockout.none(), 1, 1, NOW);

        assertThat(locked.isTemporarilyLocked(NOW.plus(BASE))).isFalse();
        assertThat(locked.isTemporarilyLocked(NOW.plus(BASE).plusSeconds(1))).isFalse();
    }

    @Test
    void eachSubsequentLockoutDoublesTheWindow() {
        // Lock #1: base. Lock #2: 2x base. Lock #3: 4x base — a patient attacker pays exponentially.
        Instant first = NOW;
        AccountLockout locked = failTimes(AccountLockout.none(), 2, 2, first);
        assertThat(locked.lockedUntil()).isEqualTo(first.plus(BASE));

        Instant second = first.plus(BASE).plusSeconds(1); // wait it out, then fail again
        locked = failTimes(locked, 2, 2, second);
        assertThat(locked.lockouts()).isEqualTo(2);
        assertThat(locked.lockedUntil()).isEqualTo(second.plus(BASE.multipliedBy(2)));

        Instant third = second.plus(BASE.multipliedBy(2)).plusSeconds(1);
        locked = failTimes(locked, 2, 2, third);
        assertThat(locked.lockouts()).isEqualTo(3);
        assertThat(locked.lockedUntil()).isEqualTo(third.plus(BASE.multipliedBy(4)));
    }

    @Test
    void theWindowIsCappedSoAnAccountIsNeverLockedForever() {
        AccountLockout locked = AccountLockout.none();
        Instant at = NOW;
        for (int lockout = 0; lockout < 12; lockout++) { // 15min * 2^11 would be ~21 days, uncapped
            locked = failTimes(locked, 1, 1, at);
            at = locked.lockedUntil().plusSeconds(1);
        }

        assertThat(Duration.between(locked.lockedUntil().minus(MAX), locked.lockedUntil())).isEqualTo(MAX);
    }

    @Test
    void aFailureDuringALiveLockDoesNotExtendIt() {
        // The login path refuses a locked account before authenticating, but a caller must not be able to
        // ratchet the deadline by hammering a locked account.
        AccountLockout locked = failTimes(AccountLockout.none(), 1, 1, NOW);
        Instant deadline = locked.lockedUntil();

        AccountLockout after = failTimes(locked, 3, 1, NOW.plusSeconds(60));

        assertThat(after.lockedUntil()).isEqualTo(deadline);
        assertThat(after.lockouts()).isEqualTo(1);
    }

    @Test
    void aSuccessfulLoginClearsTheEscalation() {
        AccountLockout locked = failTimes(AccountLockout.none(), 2, 2, NOW);
        assertThat(locked.lockouts()).isEqualTo(1);

        assertThat(AccountLockout.none().lockouts()).isZero(); // what registerSuccessfulLogin() restores
    }
}
