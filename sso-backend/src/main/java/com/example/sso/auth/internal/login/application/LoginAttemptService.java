package com.example.sso.auth.internal.login.application;

import com.example.sso.user.account.LockoutPolicy;
import com.example.sso.user.account.UserService;
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
    private final LockoutPolicy policy;

    public LoginAttemptService(UserService userService,
                               @Value("${sso.lockout.max-attempts}") int maxAttempts,
                               @Value("${sso.lockout.duration-minutes}") long lockMinutes,
                               @Value("${sso.lockout.max-duration-minutes}") long maxLockMinutes) {
        this.userService = userService;
        this.policy = new LockoutPolicy(maxAttempts, Duration.ofMinutes(lockMinutes),
                Duration.ofMinutes(maxLockMinutes));
    }

    public void onFailure(String username) {
        userService.recordFailedLogin(username, policy);
    }

    public void onSuccess(String username) {
        userService.recordSuccessfulLogin(username);
    }
}
