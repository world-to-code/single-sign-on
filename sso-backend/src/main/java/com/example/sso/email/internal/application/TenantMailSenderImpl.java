package com.example.sso.email.internal.application;

import com.example.sso.email.TenantMailSender;
import com.example.sso.shared.net.OutboundHostValidator;
import com.example.sso.tenancy.OrgContext;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Routes a send through the acting tenant's SMTP relay (resolved from the ambient {@link OrgContext}) when it
 * has one, else the platform default {@link JavaMailSender}. RESOLVING or VALIDATING the tenant relay is never
 * allowed to fail a send — a missing config, a decrypt error, or a host that now resolves to an internal
 * address all fall back to the platform sender, logged. (A failure of the delivery itself — the relay is down
 * or refuses AUTH — is NOT caught here: it propagates so the async handler records it, rather than silently
 * re-routing a tenant's mail through the platform relay.) The stored host is re-validated immediately before
 * the sender is built, so a host repointed to an internal address after configuration cannot turn a send into
 * an SSRF.
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
    public void send(SimpleMailMessage message) {
        senderFor(orgContext.currentOrg().orElse(null), message).send(message);
    }

    private JavaMailSender senderFor(UUID orgId, SimpleMailMessage message) {
        try {
            Optional<MailServer> resolved = settings.resolve(orgId);
            if (resolved.isEmpty()) {
                return platformSender;
            }
            MailServer server = resolved.get();
            hostValidator.validate(server.host()); // re-validate before connect (catches a repoint to internal)
            JavaMailSender sender = connections.create(server);
            if (StringUtils.hasText(server.fromAddress()) && message.getFrom() == null) {
                message.setFrom(server.fromAddress());
            }
            return sender;
        } catch (RuntimeException e) {
            log.warn("tenant SMTP unavailable/invalid; falling back to the platform default: {}", e.getMessage());
            return platformSender;
        }
    }
}
