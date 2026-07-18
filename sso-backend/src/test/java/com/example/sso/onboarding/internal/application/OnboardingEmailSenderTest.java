package com.example.sso.onboarding.internal.application;

import com.example.sso.email.TenantMailSender;
import com.example.sso.email.template.EmailComposer;
import com.example.sso.email.template.EmailEvent;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The admin-ONBOARDING invitation (set-password) MUST land on the NEW tenant's OWN host ({slug} substituted):
 * that flow provisions the org UP FRONT, so its subdomain already resolves and the host-bound login lands in
 * the right tenant. The self-service SIGNUP verification (activate) is the OPPOSITE: nothing is provisioned
 * yet — the tenant subdomain does NOT exist until activation creates the org — so that link must target the
 * PLATFORM host (a {slug} link would 404 at the unknown-subdomain guard); the SPA redirects to the tenant
 * subdomain only AFTER activation. The link is now passed to the composer as a template variable.
 */
class OnboardingEmailSenderTest {

    private final TenantMailSender mailSender = mock(TenantMailSender.class);
    private final EmailComposer composer = mock(EmailComposer.class);
    private final OnboardingEmailSender sender = new OnboardingEmailSender(mailSender, composer);

    @BeforeEach
    void configureTenantAwareUrls() {
        ReflectionTestUtils.setField(sender, "setPasswordUrl", "http://{slug}.localhost:9000/set-password");
        ReflectionTestUtils.setField(sender, "activateUrl", "http://localhost:9000/activate");
        ReflectionTestUtils.setField(sender, "workspaceUrlTemplate", "http://{slug}.localhost:9000");
    }

    private Map<String, Object> composedVars(EmailEvent event) {
        ArgumentCaptor<Map<String, Object>> vars = ArgumentCaptor.captor();
        verify(composer).compose(eq(event), any(), vars.capture());
        return vars.getValue();
    }

    @Test
    void theVerificationLinkTargetsThePlatformHostBecauseTheTenantDoesNotExistYet() {
        sender.sendVerification("admin@acme.example", "tok-123", "acme");

        // NOT acme.localhost — the org isn't created until this link is redeemed.
        assertThat(composedVars(EmailEvent.SIGNUP_VERIFICATION).get("activateUrl"))
                .isEqualTo("http://localhost:9000/activate?token=tok-123");
    }

    @Test
    void theInvitationSetPasswordLinkTargetsTheTenantSubdomain() {
        sender.sendInvitation("admin@acme.example", "tok-456", "acme");

        assertThat(composedVars(EmailEvent.ONBOARDING_INVITATION).get("setPasswordUrl"))
                .isEqualTo("http://acme.localhost:9000/set-password?token=tok-456");
    }
}
