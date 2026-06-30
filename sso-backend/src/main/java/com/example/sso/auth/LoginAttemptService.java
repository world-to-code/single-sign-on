package com.example.sso.auth;

import com.example.sso.user.AppUser;
import com.example.sso.user.AppUserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Tracks per-account login failures and applies a temporary lockout after too many, defending
 * against password brute-force (complements the per-IP {@code AuthRateLimitFilter}). The lock is
 * enforced via {@code accountLocked} in the UserDetails mapping, so the AuthenticationProvider
 * rejects logins while a lockout is active.
 */
@Service
public class LoginAttemptService {

    private final AppUserRepository users;
    private final int maxAttempts;
    private final Duration lockDuration;

    public LoginAttemptService(AppUserRepository users,
                               @Value("${sso.lockout.max-attempts:5}") int maxAttempts,
                               @Value("${sso.lockout.duration-minutes:15}") long lockMinutes) {
        this.users = users;
        this.maxAttempts = maxAttempts;
        this.lockDuration = Duration.ofMinutes(lockMinutes);
    }

    @Transactional
    public void onFailure(String username) {
        users.findByUsername(username)
                .ifPresent(user -> user.registerFailedLogin(maxAttempts, lockDuration, Instant.now()));
    }

    @Transactional
    public void onSuccess(String username) {
        users.findByUsername(username).ifPresent(AppUser::registerSuccessfulLogin);
    }
}
