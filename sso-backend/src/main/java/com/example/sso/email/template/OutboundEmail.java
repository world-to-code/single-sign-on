package com.example.sso.email.template;

/**
 * A fully-rendered message ready to send: the recipient, the subject, and both an HTML body and a plain-text
 * alternative (mail clients that cannot render HTML fall back to the text). Produced by {@link EmailComposer}
 * from a tenant's template (or the built-in default) and consumed by the tenant mail sender.
 */
public record OutboundEmail(String to, String subject, String htmlBody, String textBody) {
}
