package com.example.sso.auth.internal.application;

import com.example.sso.authpolicy.AuthFactor;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserService;
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
        return userService.verifyPassword(user.getUsername(), verification.password());
    }
}
