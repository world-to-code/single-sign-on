package com.example.sso.email.internal.application;

import com.example.sso.email.template.EmailEvent;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The built-in fallback template per {@link EmailEvent}, used when neither the acting tenant nor the platform
 * has configured one. The plain-text body reproduces the wording the system sent before templates existed (so
 * an un-customized tenant sees no change); the HTML body is a simple branded layout carrying the same content,
 * with an optional logo. All are Mustache strings interpolated with the event's variables at render time.
 */
@Component
class DefaultEmailTemplates {

    private final Map<EmailEvent, EmailTemplateContent> defaults = new EnumMap<>(EmailEvent.class);

    DefaultEmailTemplates() {
        defaults.put(EmailEvent.EMAIL_VERIFICATION_CODE, new EmailTemplateContent(
                "Verify your email for Mini SSO",
                html("""
                        <h1 style="font-size:20px;margin:0 0 16px;">Verify your email</h1>
                        <p style="margin:0 0 8px;">Your verification code is:</p>
                        <p style="font-size:28px;font-weight:700;letter-spacing:4px;margin:0 0 16px;">{{code}}</p>
                        <p style="color:#666;margin:0;">It expires in {{ttlMinutes}} minutes.</p>"""),
                "Your verification code is: {{code}}\n\nIt expires in {{ttlMinutes}} minutes.",
                null));

        defaults.put(EmailEvent.SIGNUP_VERIFICATION, new EmailTemplateContent(
                "Verify your email to create your Mini SSO workspace",
                html("""
                        <h1 style="font-size:20px;margin:0 0 16px;">Create your workspace</h1>
                        <p style="margin:0 0 16px;">A workspace "<strong>{{slug}}</strong>" was requested on Mini SSO
                          with this email address.</p>
                        <p style="margin:0 0 16px;">Verify your email and set your admin password to create it:</p>
                        <p style="margin:0 0 16px;"><a href="{{activateUrl}}"
                          style="background:#111;color:#fff;padding:10px 18px;border-radius:8px;
                          text-decoration:none;display:inline-block;">Verify and create workspace</a></p>
                        <p style="color:#666;margin:0;">If you didn't request this, ignore this email — nothing has
                          been created. This one-time link expires soon.</p>"""),
                "A workspace \"{{slug}}\" was requested on Mini SSO with this email address."
                        + "\n\nVerify your email and set your admin password to create it:\n\n{{activateUrl}}"
                        + "\n\nIf you didn't request this, ignore this email — nothing has been created."
                        + " This one-time link expires soon.",
                null));

        defaults.put(EmailEvent.ONBOARDING_INVITATION, new EmailTemplateContent(
                "Set up your Mini SSO admin account",
                html("""
                        <h1 style="font-size:20px;margin:0 0 16px;">Your workspace is ready</h1>
                        <p style="margin:0 0 16px;">Your workspace is ready at:<br>
                          <a href="{{workspaceUrl}}">{{workspaceUrl}}</a></p>
                        <p style="margin:0 0 16px;">Set your password to activate your admin account:</p>
                        <p style="margin:0 0 16px;"><a href="{{setPasswordUrl}}"
                          style="background:#111;color:#fff;padding:10px 18px;border-radius:8px;
                          text-decoration:none;display:inline-block;">Set your password</a></p>
                        <p style="color:#666;margin:0;">For your security this is a one-time link and expires soon.</p>"""),
                "Your workspace is ready at:\n\n{{workspaceUrl}}"
                        + "\n\nSet your password to activate your admin account:\n\n{{setPasswordUrl}}"
                        + "\n\nFor your security this is a one-time link and expires soon.",
                null));
    }

    EmailTemplateContent forEvent(EmailEvent event) {
        return defaults.get(event);
    }

    // Wrap the per-event body in a minimal, email-client-safe HTML shell with an optional logo header.
    private String html(String body) {
        return """
                <!doctype html>
                <html>
                  <body style="margin:0;padding:24px;background:#f4f4f5;
                    font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;color:#111;">
                    <div style="max-width:520px;margin:0 auto;background:#fff;border-radius:12px;padding:32px;">
                      {{#logoUrl}}<img src="{{logoUrl}}" alt="" style="max-height:48px;margin:0 0 24px;">{{/logoUrl}}
                      %s
                    </div>
                  </body>
                </html>""".formatted(body);
    }
}
