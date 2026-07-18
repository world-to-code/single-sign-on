package com.example.sso.email.internal.application;

import com.example.sso.email.internal.domain.EmailTemplate;

/**
 * The renderable content of an email template — a subject, an HTML body, a plain-text alternative, and an
 * optional logo URL — decoupled from where it came from (a persisted {@link EmailTemplate} row or a built-in
 * default). Each field is a Mustache template string the renderer interpolates the event's variables into.
 */
record EmailTemplateContent(String subject, String htmlBody, String textBody, String logoUrl) {

    static EmailTemplateContent of(EmailTemplate template) {
        return new EmailTemplateContent(template.getSubject(), template.getHtmlBody(), template.getTextBody(),
                template.getLogoUrl());
    }
}
