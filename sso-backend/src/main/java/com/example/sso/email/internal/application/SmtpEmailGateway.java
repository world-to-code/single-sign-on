package com.example.sso.email.internal.application;

import com.example.sso.email.EmailProvider;
import com.example.sso.email.template.OutboundEmail;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * A relay the tenant operates or rents, reached on a submission port.
 *
 * <p>The message goes as multipart: plain text with an HTML alternative. The host is re-validated by the
 * router before it gets here, so that a repoint to an internal address degrades to the platform sender rather
 * than failing the send.
 */
@Component
@RequiredArgsConstructor
class SmtpEmailGateway implements EmailGateway {

    private final MailServerConnectionFactory connections;

    @Override
    public EmailProvider provider() {
        return EmailProvider.SMTP;
    }

    @Override
    public void send(MailServer account, OutboundEmail email) {
        deliver(connections.create(account), account.fromAddress(), email);
    }

    /** Also used for the deployment's own relay, which has no tenant row behind it. */
    void deliver(JavaMailSender sender, String fromAddress, OutboundEmail email) {
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message,
                    MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED, StandardCharsets.UTF_8.name());
            helper.setTo(email.to());
            helper.setSubject(email.subject());
            helper.setText(email.textBody(), email.htmlBody()); // plain-text first, HTML alternative second
            if (StringUtils.hasText(fromAddress)) {
                helper.setFrom(fromAddress);
            }
            sender.send(message);
        } catch (MessagingException e) {
            throw new IllegalStateException("failed to build the outbound email", e); // reaches the async handler
        }
    }
}
