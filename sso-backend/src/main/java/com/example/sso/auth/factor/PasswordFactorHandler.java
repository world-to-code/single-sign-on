package com.example.sso.auth.factor;

import com.example.sso.authpolicy.AuthFactor;
import com.example.sso.user.AppUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Password as a step factor (e.g. step-up after a passwordless passkey login). */
@Component
public class PasswordFactorHandler implements FactorHandler {

    private final PasswordEncoder passwordEncoder;

    public PasswordFactorHandler(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public AuthFactor factor() {
        return AuthFactor.PASSWORD;
    }

    @Override
    public boolean isEnrolled(AppUser user) {
        return user.getPasswordHash() != null;
    }

    @Override
    public boolean verify(AppUser user, FactorVerificationRequest verification, HttpServletRequest request) {
        return verification.password() != null
                && passwordEncoder.matches(verification.password(), user.getPasswordHash());
    }
}
