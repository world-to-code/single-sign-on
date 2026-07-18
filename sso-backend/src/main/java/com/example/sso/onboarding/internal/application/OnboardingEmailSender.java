package com.example.sso.onboarding.internal.application;

import com.example.sso.email.TenantMailSender;
import com.example.sso.email.template.EmailComposer;
import com.example.sso.email.template.EmailEvent;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Emails the onboarding admin their one-time set-password link (the raw token lives only in this link). */
@Component
@RequiredArgsConstructor
class OnboardingEmailSender {

    private final TenantMailSender mailSender;
    private final EmailComposer composer;

    // All three carry a {slug} placeholder substituted with the new tenant's subdomain: the set-password and
    // activation links MUST land on the tenant's OWN host (their session/login context is host-bound), not the
    // bare platform host.
    @Value("${sso.onboarding.set-password-url}")
    private String setPasswordUrl;

    @Value("${sso.onboarding.activate-url}")
    private String activateUrl;

    @Value("${sso.onboarding.workspace-url-template}")
    private String workspaceUrlTemplate;

    /**
     * Emails a public self-service applicant a one-time link to VERIFY their email and set their password —
     * which is what actually creates the workspace. Sent before anything is provisioned, so it doubles as the
     * "you (or someone) requested this; nothing was created yet" notice to an unwitting recipient.
     */
    void sendVerification(String adminEmail, String rawToken, String slug) {
        String activate = activateUrl.replace("{slug}", slug) + "?token=" + rawToken;
        mailSender.send(composer.compose(EmailEvent.SIGNUP_VERIFICATION, adminEmail,
                Map.of("slug", slug, "activateUrl", activate)));
    }

    void sendInvitation(String adminEmail, String rawToken, String slug) {
        String workspaceUrl = workspaceUrlTemplate.replace("{slug}", slug);
        String setPassword = setPasswordUrl.replace("{slug}", slug) + "?token=" + rawToken;
        mailSender.send(composer.compose(EmailEvent.ONBOARDING_INVITATION, adminEmail,
                Map.of("workspaceUrl", workspaceUrl, "setPasswordUrl", setPassword, "slug", slug)));
    }
}
