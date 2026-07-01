package com.example.sso.auth.internal.application;

import com.example.sso.authpolicy.AuthFactor;
import com.example.sso.mfa.EmailVerificationService;
import com.example.sso.user.UserAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;

/** Email one-time-code factor: prepare() emails a code, verify() checks it (10-minute TTL). */
@Component
public class EmailFactorHandler implements FactorHandler {

    private static final String CODE = "EMAIL_FACTOR_CODE";
    private static final String EXPIRES_AT = "EMAIL_FACTOR_EXPIRES_AT";
    private static final String ATTEMPTS = "EMAIL_FACTOR_ATTEMPTS";

    private final EmailVerificationService emails;
    private final Duration ttl;
    private final int maxAttempts; // wrong guesses before the code is burned (a 6-digit code => ~n/1,000,000 odds)

    public EmailFactorHandler(EmailVerificationService emails,
                              @Value("${sso.email-otp.ttl-minutes:10}") long ttlMinutes,
                              @Value("${sso.email-otp.max-attempts:5}") int maxAttempts) {
        this.emails = emails;
        this.ttl = Duration.ofMinutes(ttlMinutes);
        this.maxAttempts = maxAttempts;
    }

    @Override
    public AuthFactor factor() {
        return AuthFactor.EMAIL;
    }

    @Override
    public FactorChallenge prepare(UserAccount user, HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        String code = emails.generateCode();
        session.setAttribute(CODE, code);
        session.setAttribute(EXPIRES_AT, Instant.now().plus(ttl).toEpochMilli());
        session.setAttribute(ATTEMPTS, 0); // fresh code -> reset the guess counter

        emails.sendCode(user.getEmail(), code);
        return FactorChallenge.sent();
    }

    @Override
    public boolean verify(UserAccount user, FactorVerificationRequest verification, HttpServletRequest request) {
        if (verification.code() == null) {
            return false;
        }

        HttpSession session = request.getSession(false);
        if (session == null) {
            return false;
        }

        String expected = (String) session.getAttribute(CODE);
        Object expiresAt = session.getAttribute(EXPIRES_AT);
        if (expected == null || !(expiresAt instanceof Long expiry) || System.currentTimeMillis() > expiry) {
            return false;
        }

        // Constant-time compare so a near-miss code can't be distinguished by response timing.
        if (MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                verification.code().trim().getBytes(StandardCharsets.UTF_8))) {
            clear(session);
            return true;
        }

        // Wrong code: count the attempt and burn the code after too many guesses (force a re-send).
        int attempts = (session.getAttribute(ATTEMPTS) instanceof Integer a ? a : 0) + 1;
        if (attempts >= maxAttempts) {
            clear(session);
        } else {
            session.setAttribute(ATTEMPTS, attempts);
        }

        return false;
    }

    private static void clear(HttpSession session) {
        session.removeAttribute(CODE);
        session.removeAttribute(EXPIRES_AT);
        session.removeAttribute(ATTEMPTS);
    }
}
