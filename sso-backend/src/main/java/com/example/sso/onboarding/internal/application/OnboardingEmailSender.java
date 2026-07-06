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

    @Value("${sso.onboarding.set-password-url}")
    private String setPasswordUrl;

    void sendInvitation(String adminEmail, String rawToken) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(adminEmail);
        message.setSubject("Set up your Mini SSO admin account");
        message.setText("Your workspace is ready. Set your password to activate your admin account:\n\n"
                + setPasswordUrl + "?token=" + rawToken
                + "\n\nFor your security this is a one-time link and expires soon.");
        mailSender.send(message);
    }
}
