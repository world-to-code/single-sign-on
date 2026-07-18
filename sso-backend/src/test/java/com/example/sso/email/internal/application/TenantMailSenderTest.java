package com.example.sso.email.internal.application;

import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.net.OutboundHostValidator;
import com.example.sso.tenancy.OrgContext;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.SimpleMailMessage;
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
 * to the platform sender. A configured tenant relay is re-validated (repoint defence) and its From applied.
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

    private MailServer relay(String host, String from) {
        return new MailServer(host, 587, "postmaster", "s3cret", from, true);
    }

    @Test
    void noTenantConfigUsesThePlatformSender() {
        when(settings.resolve(ORG)).thenReturn(Optional.empty());
        SimpleMailMessage message = new SimpleMailMessage();

        sender.send(message);

        verify(platformSender).send(message);
    }

    @Test
    void aResolveFailureFallsBackToThePlatformSenderWithoutThrowing() {
        when(settings.resolve(ORG)).thenThrow(new IllegalStateException("decrypt boom"));
        SimpleMailMessage message = new SimpleMailMessage();

        assertThatCode(() -> sender.send(message)).doesNotThrowAnyException();
        verify(platformSender).send(message);
    }

    @Test
    void aConfiguredTenantRelayRevalidatesTheHostAppliesFromAndUsesItsOwnSender() {
        JavaMailSender tenantSender = mock(JavaMailSender.class);
        when(settings.resolve(ORG)).thenReturn(Optional.of(relay("smtp.acme.example", "no-reply@acme.example")));
        when(connections.create(any())).thenReturn(tenantSender);
        SimpleMailMessage message = new SimpleMailMessage();

        sender.send(message);

        verify(hostValidator).validate("smtp.acme.example"); // re-validated before connect
        verify(tenantSender).send(message);
        verify(platformSender, never()).send(any(SimpleMailMessage.class));
        assertThat(message.getFrom()).isEqualTo("no-reply@acme.example");
    }

    @Test
    void aHostThatRepointsToAnInternalAddressAtSendTimeFallsBackToThePlatformSender() {
        when(settings.resolve(ORG)).thenReturn(Optional.of(relay("rebound.acme.example", "x@acme.example")));
        doThrow(new BadRequestException("internal address")).when(hostValidator).validate("rebound.acme.example");
        SimpleMailMessage message = new SimpleMailMessage();

        assertThatCode(() -> sender.send(message)).doesNotThrowAnyException();
        verify(platformSender).send(message); // never connect to the repointed internal host
    }
}
