package com.example.sso.email.internal.application;

import com.example.sso.email.template.EmailEvent;
import com.example.sso.email.template.OutboundEmail;
import com.example.sso.shared.error.BadRequestException;
import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.MustacheException;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Renders a resolved {@link EmailTemplateContent} into an {@link OutboundEmail}, logic-less. The HTML body
 * escapes interpolated values (a value like {@code "<script>"} becomes inert text — no markup injection); the
 * subject and plain-text body are rendered unescaped (they are not HTML). Only the passed variables plus
 * {@code logoUrl} reach the context, and an unknown {@code {{x}}} renders empty — never an error, never
 * server-side evaluation. Shared by the send path (via {@code EmailComposer}) and the admin preview.
 */
@Component
class EmailTemplateRenderer {

    // defaultValue("") makes a missing OR null variable render empty (never throw); it sets jmustache's
    // missingIsNull flag, which a trailing nullValue(...) would reset — so it is the ONLY leniency call here.
    private final Mustache.Compiler htmlCompiler = Mustache.compiler().escapeHTML(true).defaultValue("");
    private final Mustache.Compiler plainCompiler = Mustache.compiler().escapeHTML(false).defaultValue("");

    OutboundEmail render(String to, EmailTemplateContent content, Map<String, Object> vars) {
        Map<String, Object> context = new HashMap<>(vars);
        context.put(EmailEvent.LOGO_URL, content.logoUrl()); // may be null → the {{#logoUrl}} section is skipped

        String subject = singleLine(plainCompiler.compile(content.subject()).execute(context));
        String html = htmlCompiler.compile(content.htmlBody()).execute(context);
        String text = plainCompiler.compile(content.textBody()).execute(context);
        return new OutboundEmail(to, subject, html, text);
    }

    /**
     * Rejects a template that will not COMPILE (an unclosed section/tag) with a 400 at save time, so a malformed
     * template can never throw at send time and silently drop a verification code or 500 a public signup. Syntax
     * only — unknown variables are tolerated at render (they resolve empty).
     */
    void validateSyntax(String... templates) {
        for (String template : templates) {
            if (template == null) {
                continue;
            }
            try {
                plainCompiler.compile(template);
            } catch (MustacheException e) {
                throw BadRequestException.of("email.template.syntaxInvalid");
            }
        }
    }

    // A subject is a single header line: strip any CR/LF a template could carry, so it cannot inject a header.
    private String singleLine(String value) {
        return StringUtils.hasText(value) ? value.replaceAll("[\\r\\n]+", " ").trim() : value;
    }
}
