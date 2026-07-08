package com.example.sso.onboarding.internal.application;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** Emails the onboarding admin their one-time set-password link (the raw token lives only in this link). */
@Component
@RequiredArgsConstructor
class OnboardingEmailSender {

    private final JavaMailSender mailSender;

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
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(adminEmail);
        message.setSubject("Verify your email to create your Mini SSO workspace");
        message.setText("A workspace \"" + slug + "\" was requested on Mini SSO with this email address."
                + "\n\nVerify your email and set your admin password to create it:\n\n"
                + activateUrl.replace("{slug}", slug) + "?token=" + rawToken
                + "\n\nIf you didn't request this, ignore this email — nothing has been created."
                + " This one-time link expires soon.");
        mailSender.send(message);
    }

    void sendInvitation(String adminEmail, String rawToken, String slug) {
        String workspaceUrl = workspaceUrlTemplate.replace("{slug}", slug);
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(adminEmail);
        message.setSubject("Set up your Mini SSO admin account");
        message.setText("Your workspace is ready at:\n\n" + workspaceUrl
                + "\n\nSet your password to activate your admin account:\n\n"
                + setPasswordUrl.replace("{slug}", slug) + "?token=" + rawToken
                + "\n\nFor your security this is a one-time link and expires soon.");
        mailSender.send(message);
    }
}
