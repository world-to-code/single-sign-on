package com.example.sso.auth.internal.factor.application;

import com.example.sso.authpolicy.factor.AuthFactor;
import com.example.sso.mfa.SmsVerificationService;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.user.account.UserAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** SMS one-time-code factor: prepare() texts a code, verify() checks it (validity per sso.sms-otp.ttl-minutes). */
@Component
public class SmsFactorHandler implements FactorHandler {

    private final SmsVerificationService sms;
    private final SessionOtpChallenge challenge;
    private final Duration codeValidFor;

    public SmsFactorHandler(SmsVerificationService sms,
                            @Value("${sso.sms-otp.ttl-minutes}") long ttlMinutes,
                            @Value("${sso.sms-otp.max-attempts}") int maxAttempts) {
        this.sms = sms;
        this.codeValidFor = Duration.ofMinutes(ttlMinutes);
        this.challenge = new SessionOtpChallenge("SMS_FACTOR", Duration.ofMinutes(ttlMinutes), maxAttempts);
    }

    @Override
    public AuthFactor factor() {
        return AuthFactor.SMS;
    }

    /** Enrolment for this factor is a verified number — the same condition {@code prepare} and {@code verify}
     *  both re-check. The interface default answered "yes" for an account that has never proven one. */
    @Override
    public boolean isEnrolled(UserAccount user) {
        return user.isPhoneVerified();
    }

    @Override
    public FactorChallenge prepare(UserAccount user, HttpServletRequest request) {
        requireVerifiedPhone(user);
        String code = sms.generateCode();
        String deliveryKey = request.getSession(true).getId();
        // Cleared HERE, synchronously: leaving it to the send would let a resend followed quickly by a guess
        // report the previous attempt's failure as this one's.
        sms.clearDeliveryFailure(deliveryKey);
        challenge.issue(request.getSession(true), code);
        sms.sendCode(user.getOrgId(), user.getPhoneNumber(), code, deliveryKey);
        return FactorChallenge.sent(codeValidFor);
    }

    @Override
    public FactorVerificationResult verify(UserAccount user, FactorVerificationRequest verification,
                                           HttpServletRequest request) {
        // Re-checked here too: a code minted before the number changed must not still authenticate.
        if (!user.isPhoneVerified()) {
            return FactorVerificationResult.incorrect();
        }
        requireCodeWasDelivered(request);
        return challenge.verify(request.getSession(false), verification.code());
    }

    @Override
    public boolean deliveryFailed(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session != null && sms.deliveryFailed(session.getId());
    }

    /**
     * "That code is wrong" is the wrong answer when no code was ever sent. The send happens off the request
     * thread, so its failure lands after the "we texted you" response has gone; this is the first moment the
     * person can be told. No attempt is consumed either — there was nothing to get wrong.
     */
    private void requireCodeWasDelivered(HttpServletRequest request) {
        if (deliveryFailed(request)) {
            throw BadRequestException.of("auth.factor.sms.notDelivered");
        }
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
