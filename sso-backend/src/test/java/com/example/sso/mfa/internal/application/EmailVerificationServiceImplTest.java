package com.example.sso.mfa.internal.application;

import com.example.sso.email.template.EmailEvent;
import com.example.sso.email.template.EmailRequested;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link EmailVerificationServiceImpl}: it mints a 6-digit code and PUBLISHES an
 * {@link EmailRequested} carrying the code + TTL and the tenant to send it for (null for a global recipient).
 * The compose+send itself lives in the email module's listener (tested there); this pins the request contract.
 */
@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceImplTest {

    private static final UUID ORG = UUID.randomUUID();

    @Mock
    ApplicationEventPublisher events;

    private EmailVerificationServiceImpl service() {
        return new EmailVerificationServiceImpl(events, 10);
    }

    @Test
    void generateCodeIsSixDigits() {
        for (int i = 0; i < 100; i++) {
            assertThat(service().generateCode()).matches("\\d{6}");
        }
    }

    @Test
    void sendCodePublishesAnEmailRequestForTheTenantWithTheCodeAndTtl() {
        service().sendCode(ORG, "alice@example.com", "123456");

        ArgumentCaptor<EmailRequested> event = ArgumentCaptor.captor();
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().kind()).isEqualTo(EmailEvent.EMAIL_VERIFICATION_CODE);
        assertThat(event.getValue().recipient()).isEqualTo("alice@example.com");
        assertThat(event.getValue().orgId()).isEqualTo(ORG); // the listener re-binds this tenant off-thread
        assertThat(event.getValue().variables()).containsEntry("code", "123456").containsEntry("ttlMinutes", 10L);
    }

    @Test
    void aGlobalRecipientPublishesWithANullOrg() {
        service().sendCode(null, "root@example.com", "654321");

        ArgumentCaptor<EmailRequested> event = ArgumentCaptor.captor();
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().orgId()).isNull(); // → default relay/template
        assertThat(event.getValue().recipient()).isEqualTo("root@example.com");
        assertThat(event.getValue().variables()).containsEntry("code", "654321").containsEntry("ttlMinutes", 10L);
    }
}
