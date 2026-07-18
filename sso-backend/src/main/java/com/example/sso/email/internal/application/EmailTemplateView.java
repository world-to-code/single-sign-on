package com.example.sso.email.internal.application;

import com.example.sso.email.template.EmailEvent;
import java.util.Set;

/**
 * One event's template for the admin editor. {@code configured} is false when the acting tier has no own row
 * (it inherits the platform/built-in default) — the other fields then carry that DEFAULT content so the editor
 * opens with a sensible starting point. {@code variables} advertises exactly the names a template for this
 * event may reference (plus {@code logoUrl}).
 */
public record EmailTemplateView(EmailEvent event, boolean configured, String subject, String htmlBody,
                                String textBody, String logoUrl, Set<String> variables) {
}
