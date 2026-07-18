package com.example.sso.email.internal.application;

import com.example.sso.email.TenantMailSender;
import com.example.sso.email.template.OutboundEmail;
import com.example.sso.shared.net.OutboundHostValidator;
import com.example.sso.tenancy.OrgContext;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Routes a send through the acting tenant's SMTP relay (resolved from the ambient {@link OrgContext}) when it
 * has one, else the platform default {@link JavaMailSender}. RESOLVING or VALIDATING the tenant relay is never
 * allowed to fail a send — a missing config, a decrypt error, or a host that now resolves to an internal
 * address all fall back to the platform sender, logged. (A failure of the DELIVERY itself — the relay is down
 * or refuses AUTH — is NOT caught here: it propagates so the async handler records it, rather than silently
 * re-routing a tenant's mail through the platform relay.) The stored host is re-validated immediately before
 * the sender is built, so a host repointed to an internal address after configuration cannot turn a send into
 * an SSRF. The message is sent as multipart: a plain-text body with an HTML alternative.
 */
@Component
@RequiredArgsConstructor
class TenantMailSenderImpl implements TenantMailSender {

    private static final Logger log = LoggerFactory.getLogger(TenantMailSenderImpl.class);

    private final JavaMailSender platformSender;
    private final SmtpSettingsService settings;
    private final OrgContext orgContext;
    private final OutboundHostValidator hostValidator;
    private final MailServerConnectionFactory connections;

    @Override
    public void send(OutboundEmail email) {
        MailRelay relay = relayFor(orgContext.currentOrg().orElse(null));
        try {
            MimeMessage message = relay.sender().createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message,
                    MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED, StandardCharsets.UTF_8.name());
            helper.setTo(email.to());
            helper.setSubject(email.subject());
            helper.setText(email.textBody(), email.htmlBody()); // plain-text first, HTML alternative second
            if (StringUtils.hasText(relay.fromAddress())) {
                helper.setFrom(relay.fromAddress());
            }
            relay.sender().send(message);
        } catch (MessagingException e) {
            throw new IllegalStateException("failed to build the outbound email", e); // surfaces to the async handler
        }
    }

    private MailRelay relayFor(UUID orgId) {
        try {
            Optional<MailServer> resolved = settings.resolve(orgId);
            if (resolved.isEmpty()) {
                return new MailRelay(platformSender, null);
            }
            MailServer server = resolved.get();
            hostValidator.validate(server.host()); // re-validate before connect (catches a repoint to internal)
            return new MailRelay(connections.create(server), server.fromAddress());
        } catch (RuntimeException e) {
            log.warn("tenant SMTP unavailable/invalid; falling back to the platform default: {}", e.getMessage());
            return new MailRelay(platformSender, null);
        }
    }
}
