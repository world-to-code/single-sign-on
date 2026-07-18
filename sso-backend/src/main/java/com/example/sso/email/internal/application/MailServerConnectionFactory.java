package com.example.sso.email.internal.application;

import java.util.Properties;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

/**
 * Builds a {@link JavaMailSender} for a resolved tenant {@link MailServer}: host/port/credentials plus the TLS
 * hardening (STARTTLS or implicit-TLS required, and server-identity verification so a MITM cannot present a
 * cert for a different host) and bounded timeouts. Extracted from the routing layer ({@code TenantMailSender})
 * so the transport wiring is a collaborator with its own unit test, not a self-spied seam.
 */
@Component
class MailServerConnectionFactory {

    private static final int TIMEOUT_MS = 10_000;
    private static final int IMPLICIT_TLS_PORT = 465;

    JavaMailSender create(MailServer server) {
        JavaMailSenderImpl impl = new JavaMailSenderImpl();
        impl.setHost(server.host());
        impl.setPort(server.port());
        if (server.authenticated()) {
            impl.setUsername(server.username());
            impl.setPassword(server.password());
        }
        Properties props = impl.getJavaMailProperties();
        props.put("mail.smtp.auth", String.valueOf(server.authenticated()));
        props.put("mail.smtp.starttls.enable", String.valueOf(server.starttls()));
        props.put("mail.smtp.starttls.required", String.valueOf(server.starttls()));
        props.put("mail.smtp.ssl.checkserveridentity", "true"); // reject a cert that doesn't match the host
        if (server.port() == IMPLICIT_TLS_PORT) {
            props.put("mail.smtp.ssl.enable", "true");
        }
        props.put("mail.smtp.connectiontimeout", String.valueOf(TIMEOUT_MS));
        props.put("mail.smtp.timeout", String.valueOf(TIMEOUT_MS));
        props.put("mail.smtp.writetimeout", String.valueOf(TIMEOUT_MS));
        return impl;
    }
}
