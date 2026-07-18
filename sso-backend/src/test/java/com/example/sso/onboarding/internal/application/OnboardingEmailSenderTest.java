package com.example.sso.onboarding.internal.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import com.example.sso.email.TenantMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The admin-ONBOARDING invitation (set-password) MUST land on the NEW tenant's OWN host ({slug} substituted):
 * that flow provisions the org UP FRONT, so its subdomain already resolves and the host-bound login lands in
 * the right tenant. The self-service SIGNUP verification (activate) is the OPPOSITE: nothing is provisioned
 * yet — the tenant subdomain does NOT exist until activation creates the org — so that link must target the
 * PLATFORM host (a {slug} link would 404 at the unknown-subdomain guard); the SPA redirects to the tenant
 * subdomain only AFTER activation.
 */
class OnboardingEmailSenderTest {

    private final TenantMailSender mailSender = mock(TenantMailSender.class);
    private final OnboardingEmailSender sender = new OnboardingEmailSender(mailSender);

    @BeforeEach
    void configureTenantAwareUrls() {
        ReflectionTestUtils.setField(sender, "setPasswordUrl", "http://{slug}.localhost:9000/set-password");
        ReflectionTestUtils.setField(sender, "activateUrl", "http://localhost:9000/activate");
        ReflectionTestUtils.setField(sender, "workspaceUrlTemplate", "http://{slug}.localhost:9000");
    }

    private String sentBody() {
        ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(message.capture());
        return message.getValue().getText();
    }

    @Test
    void theVerificationLinkTargetsThePlatformHostBecauseTheTenantDoesNotExistYet() {
        sender.sendVerification("admin@acme.example", "tok-123", "acme");

        assertThat(sentBody()).contains("http://localhost:9000/activate?token=tok-123")
                .doesNotContain("acme.localhost:9000/activate"); // the org isn't created until this link is redeemed
    }

    @Test
    void theInvitationSetPasswordLinkTargetsTheTenantSubdomain() {
        sender.sendInvitation("admin@acme.example", "tok-456", "acme");

        assertThat(sentBody()).contains("http://acme.localhost:9000/set-password?token=tok-456")
                .doesNotContain("http://localhost:9000/set-password");
    }
}
