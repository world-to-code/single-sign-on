package com.example.sso.mfa.internal.application;

import com.example.sso.email.TenantMailSender;
import com.example.sso.email.template.EmailComposer;
import com.example.sso.email.template.EmailEvent;
import com.example.sso.mfa.EmailVerificationService;
import com.example.sso.tenancy.OrgContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Default {@link EmailVerificationService}. Generates and emails a short numeric code used for the
 * email factor / first-login email verification, rendered from the acting tenant's template.
 */
@Service
public class EmailVerificationServiceImpl implements EmailVerificationService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final TenantMailSender mailSender;
    private final EmailComposer composer;
    private final OrgContext orgContext;
    private final long ttlMinutes; // single source of truth with the email factor's TTL, for the message text

    public EmailVerificationServiceImpl(TenantMailSender mailSender, EmailComposer composer, OrgContext orgContext,
                                        @Value("${sso.email-otp.ttl-minutes:10}") long ttlMinutes) {
        this.mailSender = mailSender;
        this.composer = composer;
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
        Map<String, Object> vars = Map.of("code", code, "ttlMinutes", ttlMinutes);
        // On the async thread the request's OrgContext (a ThreadLocal) is gone — re-bind the org the code is FOR
        // so the template resolves and TenantMailSender routes through that tenant. A null orgId uses the default.
        if (orgId != null) {
            orgContext.runInOrg(orgId,
                    () -> mailSender.send(composer.compose(EmailEvent.EMAIL_VERIFICATION_CODE, email, vars)));
        } else {
            mailSender.send(composer.compose(EmailEvent.EMAIL_VERIFICATION_CODE, email, vars));
        }
    }
}
