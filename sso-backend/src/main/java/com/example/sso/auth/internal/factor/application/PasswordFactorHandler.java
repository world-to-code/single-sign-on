package com.example.sso.auth.internal.factor.application;

import com.example.sso.authpolicy.factor.AuthFactor;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/** Password as a step factor (e.g. step-up after a passwordless passkey login). */
@Component
public class PasswordFactorHandler implements FactorHandler {

    private final UserService userService;

    public PasswordFactorHandler(UserService userService) {
        this.userService = userService;
    }

    @Override
    public AuthFactor factor() {
        return AuthFactor.PASSWORD;
    }

    @Override
    public boolean isEnrolled(UserAccount user) {
        return userService.hasPassword(user.getId());
    }

    @Override
    public boolean verify(UserAccount user, FactorVerificationRequest verification, HttpServletRequest request) {
        // By id (like the TOTP factor uses user.getId()), NOT by username: the principal is already resolved,
        // and re-resolving by username would fail at step-up when the resolution org isn't this user's org.
        return userService.verifyPassword(user.getId(), verification.password());
    }
}
