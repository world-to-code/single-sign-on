package com.example.sso.email.internal.application;

/** The rendered result of a template (with sample data) for the editor's live preview — no persistence. */
public record EmailTemplatePreview(String subject, String html, String text) {
}
