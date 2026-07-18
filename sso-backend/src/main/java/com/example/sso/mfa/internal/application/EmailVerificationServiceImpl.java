package com.example.sso.mfa.internal.application;

import com.example.sso.email.TenantMailSender;
import com.example.sso.mfa.EmailVerificationService;
import com.example.sso.tenancy.OrgContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.UUID;

/**
 * Default {@link EmailVerificationService}. Generates and emails a short numeric code used for the
 * email factor / first-login email verification.
 */
@Service
public class EmailVerificationServiceImpl implements EmailVerificationService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final TenantMailSender mailSender;
    private final OrgContext orgContext;
    private final long ttlMinutes; // single source of truth with the email factor's TTL, for the message text

    public EmailVerificationServiceImpl(TenantMailSender mailSender, OrgContext orgContext,
                                        @Value("${sso.email-otp.ttl-minutes:10}") long ttlMinutes) {
        this.mailSender = mailSender;
        this.orgContext = orgContext;
        this.ttlMinutes = ttlMinutes;
    }

    @Override
    public String generateCode() {
        return String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
    }

    // Sent off the request thread: SMTP latency must not (a) block the caller, nor (b) make a code send
    // measurably slower than a no-op, which would disclose whether an address is already verified. A failure
    // surfaces via the async exception handler, not to the caller — an OTP send is optimistic regardless.
    @Async
    @Override
    public void sendCode(UUID orgId, String email, String code) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(email);
        message.setSubject("Verify your email for Mini SSO");
        message.setText("Your verification code is: " + code + "\n\nIt expires in " + ttlMinutes + " minutes.");

        // On the async thread the request's OrgContext (a ThreadLocal) is gone — re-bind the org the code is
        // FOR so TenantMailSender routes through that tenant's relay. A null orgId sends via the platform default.
        if (orgId != null) {
            orgContext.runInOrg(orgId, () -> mailSender.send(message));
        } else {
            mailSender.send(message);
        }
    }
}
