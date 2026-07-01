package com.example.sso.auth.internal.application;

import com.example.sso.user.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Tracks per-account login failures and applies a temporary lockout after too many, defending
 * against password brute-force (complements the per-IP {@code AuthRateLimitFilter}). The lock is
 * enforced via {@code accountLocked} in the UserDetails mapping, so the AuthenticationProvider
 * rejects logins while a lockout is active. The actual state change is owned by the user module.
 */
@Service
public class LoginAttemptService {

    private final UserService userService;
    private final int maxAttempts;
    private final Duration lockDuration;

    public LoginAttemptService(UserService userService,
                               @Value("${sso.lockout.max-attempts:5}") int maxAttempts,
                               @Value("${sso.lockout.duration-minutes:15}") long lockMinutes) {
        this.userService = userService;
        this.maxAttempts = maxAttempts;
        this.lockDuration = Duration.ofMinutes(lockMinutes);
    }

    public void onFailure(String username) {
        userService.recordFailedLogin(username, maxAttempts, lockDuration);
    }

    public void onSuccess(String username) {
        userService.recordSuccessfulLogin(username);
    }
}
