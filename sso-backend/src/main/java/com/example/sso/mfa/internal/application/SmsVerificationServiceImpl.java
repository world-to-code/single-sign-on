package com.example.sso.mfa.internal.application;

import com.example.sso.mfa.SmsSender;
import com.example.sso.mfa.SmsVerificationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.UUID;

/**
 * Default {@link SmsVerificationService}: a 6-digit {@code SecureRandom} code, texted via {@link SmsSender}
 * off the request thread. The org is passed to the sender explicitly (no ambient-context dependency), so no
 * {@code runInOrg} re-bind is needed on the async thread. Mirrors {@code EmailVerificationServiceImpl}.
 */
@Service
public class SmsVerificationServiceImpl implements SmsVerificationService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SmsSender sms;
    private final long ttlMinutes; // single source of truth with the SMS factor's TTL, for the message text

    public SmsVerificationServiceImpl(SmsSender sms, @Value("${sso.sms-otp.ttl-minutes}") long ttlMinutes) {
        this.sms = sms;
        this.ttlMinutes = ttlMinutes;
    }

    @Override
    public String generateCode() {
        return String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
    }

    // Sent off the request thread: the send must not (a) block the caller, nor (b) make a code send measurably
    // slower than a no-op, which would disclose whether a number is enrolled. A failure surfaces via the async
    // exception handler, not to the caller.
    @Async
    @Override
    public void sendCode(UUID orgId, String phoneNumber, String code) {
        sms.send(orgId, phoneNumber,
                "Your Mini SSO verification code is " + code + ". It expires in " + ttlMinutes + " minutes.");
    }
}
