package com.example.sso.email.internal.application;

/**
 * A validated write of one event's template: subject + HTML body (both required), an optional plain-text
 * alternative (falls back to the built-in default when blank), and an optional https logo URL.
 */
public record EmailTemplateSpec(String subject, String htmlBody, String textBody, String logoUrl) {
}
