package com.example.sso.email.internal.application;

import com.example.sso.email.EmailProvider;
import com.example.sso.email.TenantMailSender;
import com.example.sso.email.template.OutboundEmail;
import com.example.sso.shared.net.OutboundHostValidator;
import com.example.sso.tenancy.OrgContext;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Routes a send through the acting tenant's own way of sending — an SMTP relay or an HTTP provider, whichever
 * it configured — resolved from the ambient {@link OrgContext}, and otherwise through the deployment's default
 * relay.
 *
 * <p>RESOLVING the tenant's configuration is never allowed to fail a send: a missing row, a decrypt error or a
 * host that now resolves to an internal address all fall back to the platform sender, logged. A failure of the
 * DELIVERY itself — the relay is down, the API refuses the key — is NOT caught: it propagates so the failure is
 * recorded and reported, rather than a tenant's mail silently leaving through the platform relay under the
 * platform's own name.
 */
@Component
class TenantMailSenderImpl implements TenantMailSender {

    private static final Logger log = LoggerFactory.getLogger(TenantMailSenderImpl.class);

    private final JavaMailSender platformSender;
    private final SmtpSettingsService settings;
    private final OrgContext orgContext;
    private final SmtpEmailGateway smtp;
    private final OutboundHostValidator hostValidator;
    private final Map<EmailProvider, EmailGateway> gateways = new EnumMap<>(EmailProvider.class);

    TenantMailSenderImpl(JavaMailSender platformSender, SmtpSettingsService settings, OrgContext orgContext,
            SmtpEmailGateway smtp, OutboundHostValidator hostValidator, List<EmailGateway> gateways) {
        this.platformSender = platformSender;
        this.settings = settings;
        this.orgContext = orgContext;
        this.smtp = smtp;
        this.hostValidator = hostValidator;
        gateways.forEach(gateway -> this.gateways.put(gateway.provider(), gateway));
    }

    @Override
    public void send(OutboundEmail email) {
        MailServer account = tenantAccount();
        if (account == null) {
            smtp.deliver(platformSender, null, email);
            return;
        }
        EmailGateway gateway = gateways.get(account.provider());
        if (gateway == null) {
            // A provider stored by a build that had a client for it, running on one that does not. Refusing is
            // the only honest answer: the tenant configured a way to send and this deployment cannot use it.
            throw new IllegalStateException("no email client for provider " + account.provider());
        }
        gateway.send(account, email);
    }

    /** The acting tenant's configuration, or null to use the deployment's own relay. Never throws. */
    private MailServer tenantAccount() {
        UUID orgId = orgContext.currentOrg().orElse(null);
        try {
            MailServer account = settings.resolve(orgId).orElse(null);
            if (account != null && account.provider() == EmailProvider.SMTP) {
                // Re-checked HERE rather than at delivery, so a host repointed at an internal address after it
                // was configured degrades to the platform sender instead of failing the send. Treating an SSRF
                // attempt as a resolution problem is the deliberate choice: mail still goes out, from us.
                hostValidator.validate(account.host());
            }
            return account;
        } catch (RuntimeException unresolvable) {
            log.warn("tenant mail configuration unavailable; falling back to the platform default: {}",
                    unresolvable.getMessage());
            return null;
        }
    }
}
