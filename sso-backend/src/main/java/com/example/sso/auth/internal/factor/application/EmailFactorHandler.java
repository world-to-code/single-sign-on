package com.example.sso.auth.internal.factor.application;

import com.example.sso.authpolicy.factor.AuthFactor;
import com.example.sso.mfa.EmailVerificationService;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.user.account.UserAccount;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Email one-time-code factor: prepare() emails a code, verify() checks it (validity per sso.email-otp.ttl-minutes). */
@Component
public class EmailFactorHandler implements FactorHandler {

    private final EmailVerificationService emails;
    private final SessionOtpChallenge challenge;

    public EmailFactorHandler(EmailVerificationService emails,
                              @Value("${sso.email-otp.ttl-minutes:10}") long ttlMinutes,
                              @Value("${sso.email-otp.max-attempts:5}") int maxAttempts) {
        this.emails = emails;
        this.challenge = new SessionOtpChallenge("EMAIL_FACTOR", Duration.ofMinutes(ttlMinutes), maxAttempts);
    }

    @Override
    public AuthFactor factor() {
        return AuthFactor.EMAIL;
    }

    @Override
    public FactorChallenge prepare(UserAccount user, HttpServletRequest request) {
        requireVerifiedAddress(user);
        String code = emails.generateCode();
        challenge.issue(request.getSession(true), code);
        emails.sendCode(user.getOrgId(), user.getEmail(), code);
        return FactorChallenge.sent();
    }

    @Override
    public boolean verify(UserAccount user, FactorVerificationRequest verification, HttpServletRequest request) {
        // Re-checked here too: a code minted before the address changed must not still authenticate.
        if (!user.isEmailVerified()) {
            return false;
        }
        return challenge.matches(request.getSession(false), verification.code());
    }

    /**
     * A one-time code proves control of the mailbox it lands in — nothing more. Sent to an address nobody
     * proved belongs to the user (an admin can change it; {@code updateProfile} clears the flag on change),
     * accepting that code would authenticate whoever holds that mailbox. The other factors of the step remain
     * available, so this refuses the FACTOR, not the login.
     */
    private void requireVerifiedAddress(UserAccount user) {
        if (!user.isEmailVerified()) {
            throw ForbiddenException.of("auth.factor.email.unverified");
        }
    }
}
