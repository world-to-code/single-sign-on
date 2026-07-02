package com.example.sso.user.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.Duration;
import java.time.Instant;

/**
 * Brute-force lockout state for an account, as an immutable value object: the consecutive failed-login
 * count and, once the threshold is reached, the instant the temporary lock lifts. Embedded in
 * {@code AppUser}; the behavior lives here rather than scattered across the entity.
 */
@Embeddable
public record AccountLockout(
        @Column(name = "failed_login_attempts", nullable = false) int failedAttempts,
        @Column(name = "locked_until") Instant lockedUntil) {

    /** The initial "no failures, not locked" state. */
    public static AccountLockout none() {
        return new AccountLockout(0, null);
    }

    /** Records one more failure; once {@code maxAttempts} is reached, locks until {@code now + lockFor}. */
    public AccountLockout registerFailure(int maxAttempts, Duration lockFor, Instant now) {
        int attempts = failedAttempts + 1;
        Instant until = attempts >= maxAttempts ? now.plus(lockFor) : lockedUntil;
        return new AccountLockout(attempts, until);
    }

    /** True while a temporary lockout is still in effect. */
    public boolean isTemporarilyLocked(Instant now) {
        return lockedUntil != null && now.isBefore(lockedUntil);
    }
}
