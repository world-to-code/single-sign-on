package com.example.sso.email.internal.application;

import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MailServerConnectionFactory}: the built sender carries the tenant's host/port/credentials
 * and the load-bearing TLS hardening — STARTTLS required, server-identity verification on, and implicit TLS on
 * 465. A regression here would silently downgrade a tenant's SMTP-AUTH to cleartext. {@code create} does no
 * network I/O, so this asserts the wiring directly.
 */
class MailServerConnectionFactoryTest {

    private final MailServerConnectionFactory factory = new MailServerConnectionFactory();

    @Test
    void aStarttlsRelayIsWiredWithAuthAndServerIdentityVerification() {
        JavaMailSenderImpl impl = (JavaMailSenderImpl) factory.create(
                new MailServer("smtp.acme.example", 587, "postmaster", "s3cret", "no-reply@acme.example", true));

        assertThat(impl.getHost()).isEqualTo("smtp.acme.example");
        assertThat(impl.getPort()).isEqualTo(587);
        assertThat(impl.getUsername()).isEqualTo("postmaster");
        assertThat(impl.getPassword()).isEqualTo("s3cret");
        assertThat(impl.getJavaMailProperties())
                .containsEntry("mail.smtp.auth", "true")
                .containsEntry("mail.smtp.starttls.enable", "true")
                .containsEntry("mail.smtp.starttls.required", "true")           // no cleartext downgrade
                .containsEntry("mail.smtp.ssl.checkserveridentity", "true")     // reject a mismatched cert
                .doesNotContainKey("mail.smtp.ssl.enable");                     // STARTTLS, not implicit TLS
    }

    @Test
    void an465RelayEnablesImplicitTls() {
        JavaMailSenderImpl impl = (JavaMailSenderImpl) factory.create(
                new MailServer("smtp.acme.example", 465, "postmaster", "s3cret", null, false));

        assertThat(impl.getJavaMailProperties())
                .containsEntry("mail.smtp.ssl.enable", "true")                  // implicit TLS on 465
                .containsEntry("mail.smtp.ssl.checkserveridentity", "true");
    }

    @Test
    void anUnauthenticatedRelayCarriesNoCredentialsAndDisablesAuth() {
        JavaMailSenderImpl impl = (JavaMailSenderImpl) factory.create(
                new MailServer("smtp.acme.example", 587, null, null, null, true));

        assertThat(impl.getUsername()).isNull();
        assertThat(impl.getPassword()).isNull();
        assertThat(impl.getJavaMailProperties()).containsEntry("mail.smtp.auth", "false");
    }
}
