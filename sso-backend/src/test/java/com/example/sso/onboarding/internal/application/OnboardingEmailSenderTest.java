package com.example.sso.onboarding.internal.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The set-password / verification links MUST land on the NEW tenant's OWN host ({slug} substituted), not the
 * bare platform host — the admin's session/login is host-bound, so a link to {@code localhost:9000} would drop
 * them in the wrong (or no) tenant context.
 */
class OnboardingEmailSenderTest {

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final OnboardingEmailSender sender = new OnboardingEmailSender(mailSender);

    @BeforeEach
    void configureTenantAwareUrls() {
        ReflectionTestUtils.setField(sender, "setPasswordUrl", "http://{slug}.localhost:9000/set-password");
        ReflectionTestUtils.setField(sender, "activateUrl", "http://{slug}.localhost:9000/activate");
        ReflectionTestUtils.setField(sender, "workspaceUrlTemplate", "http://{slug}.localhost:9000");
    }

    private String sentBody() {
        ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(message.capture());
        return message.getValue().getText();
    }

    @Test
    void theVerificationLinkTargetsTheTenantSubdomain() {
        sender.sendVerification("admin@acme.example", "tok-123", "acme");

        assertThat(sentBody()).contains("http://acme.localhost:9000/activate?token=tok-123")
                .doesNotContain("http://localhost:9000/activate");
    }

    @Test
    void theInvitationSetPasswordLinkTargetsTheTenantSubdomain() {
        sender.sendInvitation("admin@acme.example", "tok-456", "acme");

        assertThat(sentBody()).contains("http://acme.localhost:9000/set-password?token=tok-456")
                .doesNotContain("http://localhost:9000/set-password");
    }
}
