package com.example.sso.user.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.Duration;
import java.time.Instant;

/**
 * Brute-force lockout state for an account, as an immutable value object: the consecutive failed-login count,
 * the instant the current temporary lock lifts, and how many times the account has locked since its last
 * successful sign-in. Embedded in {@code AppUser}; the behavior lives here rather than scattered across the
 * entity.
 *
 * <p>A FIXED lock window only throttles a patient attacker — they wait it out and resume. Each subsequent
 * lockout therefore doubles the window ({@code base * 2^(lockouts-1)}), bounded by a maximum so an account is
 * never locked out forever, and the escalation is cleared by a successful sign-in ({@link #none()}).
 */
@Embeddable
public record AccountLockout(
        @Column(name = "failed_login_attempts", nullable = false) int failedAttempts,
        @Column(name = "locked_until") Instant lockedUntil,
        @Column(name = "lockout_count", nullable = false) int lockouts) {

    /** The initial "no failures, never locked" state — also what a successful sign-in restores. */
    public static AccountLockout none() {
        return new AccountLockout(0, null, 0);
    }

    /**
     * Records one more failure. Once {@code maxAttempts} consecutive failures accumulate, the account locks
     * for {@code baseLockFor * 2^lockouts} (bounded by {@code maxLockFor}) and the attempt counter restarts.
     * A failure arriving while a lock is still live changes nothing — the deadline cannot be ratcheted.
     */
    public AccountLockout registerFailure(int maxAttempts, Duration baseLockFor, Duration maxLockFor,
                                          Instant now) {
        if (isTemporarilyLocked(now)) {
            return this;
        }

        int attempts = failedAttempts + 1;
        if (attempts < maxAttempts) {
            return new AccountLockout(attempts, null, lockouts);
        }

        int lockout = lockouts + 1;
        return new AccountLockout(0, now.plus(backoff(baseLockFor, maxLockFor, lockout)), lockout);
    }

    /** True while a temporary lockout is still in effect. */
    public boolean isTemporarilyLocked(Instant now) {
        return lockedUntil != null && now.isBefore(lockedUntil);
    }

    /** {@code base * 2^(lockout-1)}, saturating at {@code max} (and never overflowing on the way there). */
    private static Duration backoff(Duration base, Duration max, int lockout) {
        long millis = base.toMillis();
        long ceiling = max.toMillis();
        for (int doubling = 1; doubling < lockout && millis < ceiling; doubling++) {
            millis <<= 1;
        }
        return Duration.ofMillis(Math.min(millis, ceiling));
    }
}
