package com.example.sso.email.internal.api;

import com.example.sso.email.internal.application.EmailTemplateSpec;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Upserts (or previews) one event's email template. {@code subject}/{@code htmlBody} are required; {@code
 * textBody} is the optional plain-text alternative; {@code logoUrl}, when set, must be an https URL (loaded by
 * the recipient's mail client — validated for shape only, never fetched server-side). Bodies are rendered
 * logic-less, so their content carries no execution risk; these bounds only cap size/shape.
 */
public record EmailTemplateRequest(
        @NotBlank @Size(max = 255) String subject,
        @NotBlank @Size(max = 65536) String htmlBody,
        @Size(max = 65536) String textBody,
        @Size(max = 2048) @Pattern(regexp = "^(https://.+)?$", message = "must be an https URL") String logoUrl) {

    public EmailTemplateSpec toSpec() {
        return new EmailTemplateSpec(subject, htmlBody, textBody, logoUrl);
    }
}
