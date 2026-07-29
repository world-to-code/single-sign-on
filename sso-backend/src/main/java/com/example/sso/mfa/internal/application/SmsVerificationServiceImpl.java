package com.example.sso.mfa.internal.application;

import com.example.sso.branding.Branding;
import com.example.sso.branding.BrandingResolver;
import com.example.sso.mfa.SmsSender;
import com.example.sso.mfa.SmsVerificationService;
import com.example.sso.tenancy.OrgContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
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
    private final CodeDeliveryStatus deliveryStatus;
    private final BrandingResolver branding;
    private final OrgContext orgContext;
    private final MessageSource messages;
    private final long ttlMinutes; // single source of truth with the SMS factor's TTL, for the message text

    public SmsVerificationServiceImpl(SmsSender sms, CodeDeliveryStatus deliveryStatus,
            BrandingResolver branding, OrgContext orgContext, MessageSource messages,
            @Value("${sso.sms-otp.ttl-minutes}") long ttlMinutes) {
        this.sms = sms;
        this.deliveryStatus = deliveryStatus;
        this.branding = branding;
        this.orgContext = orgContext;
        this.messages = messages;
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
            sms.send(orgId, phoneNumber, text(orgId, code));
        } catch (RuntimeException undelivered) {
            // Recorded before rethrowing: the caller is the async handler, which logs — but the person waiting
            // for the code learns nothing from a server log.
            deliveryStatus.markFailed(deliveryKey);
            throw undelivered;
        }
    }

    /**
     * The message body: the tenant's own name first, then the code.
     *
     * <p>The name is not decoration. A code arriving from an unrecognised number saying nothing about who sent
     * it is indistinguishable from a phishing text, and Korean carriers expect the {@code [sender]} prefix that
     * makes a legitimate message recognisable. It comes from the tenant's branding, so it is the same name they
     * see on the sign-in screen.
     */
    private String text(UUID orgId, String code) {
        return messages.getMessage("mfa.sms.verification.text",
                new Object[] { productName(orgId), code, ttlMinutes }, LocaleContextHolder.getLocale());
    }

    /**
     * Resolved inside the tenant's own context, because branding is read under row-level security — the same
     * trap that made a configured gateway look unconfigured. A tenant that has set no name gets the
     * deployment's, and a lookup that fails must not stop a one-time code going out.
     */
    private String productName(UUID orgId) {
        if (orgId == null) {
            return Branding.platformDefault().productName();
        }
        try {
            Branding resolved = orgContext.callInOrg(orgId, () -> branding.resolve(orgId));
            return resolved.productName() == null || resolved.productName().isBlank()
                    ? Branding.platformDefault().productName()
                    : resolved.productName();
        } catch (RuntimeException unavailable) {
            return Branding.platformDefault().productName();
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
