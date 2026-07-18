package com.example.sso.auth.internal.factor.application;

import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;

/**
 * The session-held state machine behind a one-time-code factor: a code plus its expiry and a remaining-guess
 * counter, stored on the {@link HttpSession} under a per-factor key prefix. Shared by every code-to-a-contact
 * factor ({@code EmailFactorHandler}, {@code SmsFactorHandler}) so the security-critical parts — the
 * constant-time compare, the TTL check, and the burn-after-too-many-guesses — live in exactly ONE place and
 * cannot drift between factors.
 *
 * <p>Not a Spring bean: each handler owns an instance built with its own key prefix and its own TTL /
 * max-attempts, so two factors keep independent session slots.
 */
class SessionOtpChallenge {

    private final String codeKey;
    private final String expiresAtKey;
    private final String attemptsKey;
    private final Duration ttl;
    private final int maxAttempts; // wrong guesses before the code is burned (a 6-digit code => ~n/1,000,000 odds)

    SessionOtpChallenge(String keyPrefix, Duration ttl, int maxAttempts) {
        this.codeKey = keyPrefix + "_CODE";
        this.expiresAtKey = keyPrefix + "_EXPIRES_AT";
        this.attemptsKey = keyPrefix + "_ATTEMPTS";
        this.ttl = ttl;
        this.maxAttempts = maxAttempts;
    }

    /** Stores a freshly-minted code and resets the guess counter (a new code always starts clean). */
    void issue(HttpSession session, String code) {
        session.setAttribute(codeKey, code);
        session.setAttribute(expiresAtKey, Instant.now().plus(ttl).toEpochMilli());
        session.setAttribute(attemptsKey, 0);
    }

    /**
     * True when {@code presented} matches the live code — consuming it on success. A wrong code counts against
     * the attempt cap and is burned once the cap is reached (forcing a re-send); a missing session, expired or
     * absent code all return false.
     */
    boolean matches(HttpSession session, String presented) {
        if (session == null || presented == null) {
            return false;
        }
        String expected = (String) session.getAttribute(codeKey);
        Object expiresAt = session.getAttribute(expiresAtKey);
        if (expected == null || !(expiresAt instanceof Long expiry) || System.currentTimeMillis() > expiry) {
            return false;
        }

        // Constant-time compare so a near-miss code can't be distinguished by response timing.
        if (MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                presented.trim().getBytes(StandardCharsets.UTF_8))) {
            clear(session);
            return true;
        }

        int attempts = (session.getAttribute(attemptsKey) instanceof Integer a ? a : 0) + 1;
        if (attempts >= maxAttempts) {
            clear(session);
        } else {
            session.setAttribute(attemptsKey, attempts);
        }
        return false;
    }

    private void clear(HttpSession session) {
        session.removeAttribute(codeKey);
        session.removeAttribute(expiresAtKey);
        session.removeAttribute(attemptsKey);
    }
}
