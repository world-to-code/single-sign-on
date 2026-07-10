package com.example.sso.user;

import java.time.Duration;

/**
 * The brute-force lockout tunables applied to a failed sign-in: how many consecutive failures lock the
 * account, the first lock's length, and the ceiling the exponential backoff saturates at.
 */
public record LockoutPolicy(int maxAttempts, Duration baseLockFor, Duration maxLockFor) {
}
