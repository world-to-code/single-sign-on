package com.example.sso.mfa.internal.application;

import com.example.sso.email.template.EmailEvent;
import com.example.sso.email.template.EmailRequested;
import com.example.sso.mfa.EmailVerificationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Default {@link EmailVerificationService}. Generates a short numeric code for the email factor / first-login
 * email verification and PUBLISHES a request to email it — the email module composes the acting tenant's
 * template and sends it off-thread (see {@code EmailRequestedListener}). Publishing is instant, so the caller
 * neither blocks on SMTP nor makes a code send measurably slower than a no-op (no address-enrolled timing
 * oracle); a failed send is optimistic and surfaces only via the async handler.
 */
@Service
public class EmailVerificationServiceImpl implements EmailVerificationService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApplicationEventPublisher events;
    private final long ttlMinutes; // single source of truth with the email factor's TTL, for the message text

    public EmailVerificationServiceImpl(ApplicationEventPublisher events,
                                        @Value("${sso.email-otp.ttl-minutes:10}") long ttlMinutes) {
        this.events = events;
        this.ttlMinutes = ttlMinutes;
    }

    @Override
    public String generateCode() {
        return String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
    }

    @Override
    public void sendCode(UUID orgId, String email, String code) {
        Map<String, Object> vars = Map.of("code", code, "ttlMinutes", ttlMinutes);
        events.publishEvent(new EmailRequested(EmailEvent.EMAIL_VERIFICATION_CODE, email, vars, orgId));
    }
}
