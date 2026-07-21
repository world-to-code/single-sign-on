package com.example.sso.auth.internal.factor.application;

import com.example.sso.authpolicy.factor.AuthFactor;
import com.example.sso.mfa.SmsVerificationService;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.user.account.UserAccount;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** SMS one-time-code factor: prepare() texts a code, verify() checks it (validity per sso.sms-otp.ttl-minutes). */
@Component
public class SmsFactorHandler implements FactorHandler {

    private final SmsVerificationService sms;
    private final SessionOtpChallenge challenge;

    public SmsFactorHandler(SmsVerificationService sms,
                            @Value("${sso.sms-otp.ttl-minutes}") long ttlMinutes,
                            @Value("${sso.sms-otp.max-attempts}") int maxAttempts) {
        this.sms = sms;
        this.challenge = new SessionOtpChallenge("SMS_FACTOR", Duration.ofMinutes(ttlMinutes), maxAttempts);
    }

    @Override
    public AuthFactor factor() {
        return AuthFactor.SMS;
    }

    @Override
    public FactorChallenge prepare(UserAccount user, HttpServletRequest request) {
        requireVerifiedPhone(user);
        String code = sms.generateCode();
        challenge.issue(request.getSession(true), code);
        sms.sendCode(user.getOrgId(), user.getPhoneNumber(), code);
        return FactorChallenge.sent();
    }

    @Override
    public boolean verify(UserAccount user, FactorVerificationRequest verification, HttpServletRequest request) {
        // Re-checked here too: a code minted before the number changed must not still authenticate.
        if (!user.isPhoneVerified()) {
            return false;
        }
        return challenge.matches(request.getSession(false), verification.code());
    }

    /**
     * A one-time code proves control of the handset it lands on — nothing more. Texting it to a number nobody
     * proved belongs to the user (an admin edit, or a number changed since enrollment; {@code changePhone}
     * clears the flag on change) would authenticate whoever holds that line. The other factors of the step
     * remain available, so this refuses the FACTOR, not the login.
     */
    private void requireVerifiedPhone(UserAccount user) {
        if (!user.isPhoneVerified() || user.getPhoneNumber() == null) {
            throw ForbiddenException.of("auth.factor.sms.unverified");
        }
    }
}
