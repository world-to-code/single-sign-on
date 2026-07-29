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
 * off the request thread. The org travels as an ARGUMENT rather than in the ambient context, which is why this
 * needs no {@code runInOrg} of its own — but the sender does re-bind it before reading the tenant's gateway,
 * because that read is under row-level security and an argument does not scope a connection. Mirrors
 * {@code EmailVerificationServiceImpl}.
 */
@Service
public class SmsVerificationServiceImpl implements SmsVerificationService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SmsSender sms;
    private final SmsDeliveryStatus deliveryStatus;
    private final long ttlMinutes; // single source of truth with the SMS factor's TTL, for the message text

    public SmsVerificationServiceImpl(SmsSender sms, SmsDeliveryStatus deliveryStatus,
            @Value("${sso.sms-otp.ttl-minutes}") long ttlMinutes) {
        this.sms = sms;
        this.deliveryStatus = deliveryStatus;
        this.ttlMinutes = ttlMinutes;
    }

    @Override
    public String generateCode() {
        return String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
    }

    // Sent off the request thread: the send must not (a) block the caller, nor (b) make a code send measurably
    // slower than a no-op, which would disclose whether a number is enrolled. A failure surfaces via the async
    // exception handler, not to the caller.
    @Async("notificationExecutor")
    @Override
    public void sendCode(UUID orgId, String phoneNumber, String code, String deliveryKey) {
        try {
            sms.send(orgId, phoneNumber,
                    "Your Mini SSO verification code is " + code + ". It expires in " + ttlMinutes + " minutes.");
        } catch (RuntimeException undelivered) {
            // Recorded before rethrowing: the caller is the async handler, which logs — but the person waiting
            // for the code learns nothing from a server log.
            deliveryStatus.markFailed(deliveryKey);
            throw undelivered;
        }
    }

    @Override
    public void clearDeliveryFailure(String deliveryKey) {
        deliveryStatus.clear(deliveryKey);
    }

    @Override
    public boolean deliveryFailed(String deliveryKey) {
        return deliveryStatus.failed(deliveryKey);
    }
}
