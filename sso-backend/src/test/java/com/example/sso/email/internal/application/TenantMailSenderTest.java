package com.example.sso.email.internal.application;

import com.example.sso.email.template.OutboundEmail;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.net.OutboundHostValidator;
import com.example.sso.tenancy.OrgContext;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TenantMailSenderImpl}: a send is NEVER failed for want of a resolvable/valid tenant
 * config — a missing config, a resolve error, or a host that now resolves to an internal address all fall back
 * to the platform sender. A configured tenant relay is re-validated (repoint defence), its From applied, and
 * the message sent as a MIME (text + HTML) through the tenant's own sender.
 */
@ExtendWith(MockitoExtension.class)
class TenantMailSenderTest {

    private static final UUID ORG = UUID.randomUUID();

    @Mock
    JavaMailSender platformSender;
    @Mock
    SmtpSettingsService settings;
    @Mock
    OrgContext orgContext;
    @Mock
    OutboundHostValidator hostValidator;
    @Mock
    MailServerConnectionFactory connections;

    private TenantMailSenderImpl sender;

    @BeforeEach
    void setUp() {
        sender = new TenantMailSenderImpl(platformSender, settings, orgContext, hostValidator, connections);
        lenient().when(orgContext.currentOrg()).thenReturn(Optional.of(ORG));
    }

    private OutboundEmail email() {
        return new OutboundEmail("to@acme.example", "Verify your email", "<p>code 123456</p>", "code 123456");
    }

    private MailServer relay(String host, String from) {
        return new MailServer(host, 587, "postmaster", "s3cret", from, true);
    }

    private static MimeMessage emptyMime() {
        return new MimeMessage((Session) null);
    }

    @Test
    void noTenantConfigUsesThePlatformSender() {
        when(settings.resolve(ORG)).thenReturn(Optional.empty());
        when(platformSender.createMimeMessage()).thenReturn(emptyMime());

        sender.send(email());

        verify(platformSender).send(any(MimeMessage.class));
    }

    @Test
    void aResolveFailureFallsBackToThePlatformSenderWithoutThrowing() {
        when(settings.resolve(ORG)).thenThrow(new IllegalStateException("decrypt boom"));
        when(platformSender.createMimeMessage()).thenReturn(emptyMime());

        assertThatCode(() -> sender.send(email())).doesNotThrowAnyException();
        verify(platformSender).send(any(MimeMessage.class));
    }

    @Test
    void aConfiguredTenantRelayRevalidatesTheHostAppliesFromAndUsesItsOwnSender() throws Exception {
        JavaMailSender tenantSender = mock(JavaMailSender.class);
        when(settings.resolve(ORG)).thenReturn(Optional.of(relay("smtp.acme.example", "no-reply@acme.example")));
        when(connections.create(any())).thenReturn(tenantSender);
        when(tenantSender.createMimeMessage()).thenReturn(emptyMime());

        sender.send(email());

        verify(hostValidator).validate("smtp.acme.example"); // re-validated before connect
        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.captor();
        verify(tenantSender).send(sent.capture());
        verify(platformSender, never()).send(any(MimeMessage.class));
        assertThat(sent.getValue().getFrom()[0].toString()).isEqualTo("no-reply@acme.example");
        assertThat(sent.getValue().getSubject()).isEqualTo("Verify your email");
    }

    @Test
    void aHostThatRepointsToAnInternalAddressAtSendTimeFallsBackToThePlatformSender() {
        when(settings.resolve(ORG)).thenReturn(Optional.of(relay("rebound.acme.example", "x@acme.example")));
        doThrow(new BadRequestException("internal address")).when(hostValidator).validate("rebound.acme.example");
        when(platformSender.createMimeMessage()).thenReturn(emptyMime());

        assertThatCode(() -> sender.send(email())).doesNotThrowAnyException();
        verify(platformSender).send(any(MimeMessage.class)); // never connect to the repointed internal host
    }
}
