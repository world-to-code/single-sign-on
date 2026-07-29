package com.example.sso.email.internal.application;

import com.example.sso.email.TenantMailSender;
import com.example.sso.email.template.EmailComposer;
import com.example.sso.email.template.EmailEvent;
import com.example.sso.email.template.EmailRequested;
import com.example.sso.email.template.OutboundEmail;
import com.example.sso.tenancy.OrgContext;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.example.sso.email.template.EmailDeliveryFailed;
import java.util.Map;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EmailRequestedListener}: it composes the tenant's template from the event's kind +
 * variables and sends it, re-binding the tenant's OrgContext off-thread (the ThreadLocal does not follow the
 * async hop) so the template resolves and the relay routes per-tenant. A null orgId composes and sends without
 * a bound context. This is the compose+send that used to live in the mfa email-OTP service.
 */
@ExtendWith(MockitoExtension.class)
class EmailRequestedListenerTest {

    private static final UUID ORG = UUID.randomUUID();
    private final OutboundEmail composed = new OutboundEmail("alice@example.com", "Verify", "<p>h</p>", "t");

    @Mock
    TenantMailSender mailSender;
    @Mock
    EmailComposer composer;
    @Mock
    OrgContext orgContext;

    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);


    /**
     * The send runs off the request thread, so what it throws reaches a log and nobody who is waiting for the
     * mail. Announcing the failure is what lets the module that asked for it tell that person — and it is an
     * EVENT rather than a call back, because that module already publishes one to get here.
     */
    @Test
    void aFailedSendIsAnnouncedAndStillSurfaces() {
        doThrow(new IllegalStateException("relay down")).when(mailSender).send(any());

        assertThatThrownBy(() -> listener().onEmailRequested(
                new EmailRequested(EmailEvent.EMAIL_VERIFICATION_CODE, "a@b.example", Map.of(), null, "attempt-1")))
                .isInstanceOf(IllegalStateException.class);

        ArgumentCaptor<EmailDeliveryFailed> announced = ArgumentCaptor.forClass(EmailDeliveryFailed.class);
        verify(events).publishEvent(announced.capture());
        assertThat(announced.getValue().deliveryKey()).isEqualTo("attempt-1");
    }

    /** Mail nobody is waiting on a screen for carries no attempt, and announcing it tells nobody anything. */
    @Test
    void aFailedSendWithNoWaitingAttemptAnnouncesNoKey() {
        doThrow(new IllegalStateException("relay down")).when(mailSender).send(any());

        assertThatThrownBy(() -> listener().onEmailRequested(
                new EmailRequested(EmailEvent.EMAIL_VERIFICATION_CODE, "a@b.example", Map.of(), null)))
                .isInstanceOf(IllegalStateException.class);

        ArgumentCaptor<EmailDeliveryFailed> announced = ArgumentCaptor.forClass(EmailDeliveryFailed.class);
        verify(events).publishEvent(announced.capture());
        assertThat(announced.getValue().deliveryKey()).isNull();
    }

    /** A send that works announces nothing. */
    @Test
    void aSuccessfulSendAnnouncesNothing() {
        listener().onEmailRequested(
                new EmailRequested(EmailEvent.EMAIL_VERIFICATION_CODE, "a@b.example", Map.of(), null, "attempt-1"));

        verify(events, never()).publishEvent(any(EmailDeliveryFailed.class));
    }

    private EmailRequestedListener listener() {
        return new EmailRequestedListener(mailSender, composer, orgContext, events);
    }

    private EmailRequested request(String to, UUID orgId) {
        return new EmailRequested(EmailEvent.EMAIL_VERIFICATION_CODE, to, Map.of("code", "123456", "ttlMinutes", 10L),
                orgId);
    }

    @Test
    void aTenantEmailIsComposedAndSentWithinThatTenantsOrgContext() {
        lenient().when(composer.compose(eq(EmailEvent.EMAIL_VERIFICATION_CODE), eq("alice@example.com"), any()))
                .thenReturn(composed);
        doAnswer(inv -> {
            inv.getArgument(1, Runnable.class).run();
            return null;
        }).when(orgContext).runInOrg(any(), any());

        listener().onEmailRequested(request("alice@example.com", ORG));

        verify(orgContext).runInOrg(eq(ORG), any()); // routed via the tenant's own template + relay
        verify(mailSender).send(composed);
        ArgumentCaptor<Map<String, Object>> vars = ArgumentCaptor.captor();
        verify(composer).compose(eq(EmailEvent.EMAIL_VERIFICATION_CODE), eq("alice@example.com"), vars.capture());
        assertThat(vars.getValue()).containsEntry("code", "123456").containsEntry("ttlMinutes", 10L);
    }

    @Test
    void aGlobalRecipientSendsWithoutBindingAnOrgContext() {
        when(composer.compose(any(), any(), any())).thenReturn(composed);

        listener().onEmailRequested(request("root@example.com", null));

        verify(orgContext, never()).runInOrg(any(), any());
        verify(mailSender).send(composed); // composed with no bound org → default/platform template
        ArgumentCaptor<Map<String, Object>> vars = ArgumentCaptor.captor();
        verify(composer).compose(eq(EmailEvent.EMAIL_VERIFICATION_CODE), eq("root@example.com"), vars.capture());
        assertThat(vars.getValue()).containsEntry("code", "123456").containsEntry("ttlMinutes", 10L);
    }
}
