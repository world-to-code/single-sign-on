package com.example.sso.email.internal.application;

import com.example.sso.email.template.EmailEvent;
import com.example.sso.email.template.OutboundEmail;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The built-in defaults must render (via the real renderer) a non-empty subject/HTML/text for every event and
 * still carry each event's load-bearing token (the code, the link) — a regression that dropped the link would
 * ship an unusable email. The logo header appears only when a logo URL is supplied (a logic-less section).
 */
class DefaultEmailTemplatesTest {

    private final DefaultEmailTemplates defaults = new DefaultEmailTemplates();
    private final EmailTemplateRenderer renderer = new EmailTemplateRenderer();

    private OutboundEmail render(EmailEvent event, Map<String, Object> vars, String logoUrl) {
        EmailTemplateContent d = defaults.forEvent(event);
        return renderer.render("to@x.example",
                new EmailTemplateContent(d.subject(), d.htmlBody(), d.textBody(), logoUrl), vars);
    }

    @Test
    void everyEventHasANonEmptyDefault() {
        for (EmailEvent event : EmailEvent.values()) {
            EmailTemplateContent content = defaults.forEvent(event);
            assertThat(content).as("default for %s", event).isNotNull();
            assertThat(content.subject()).as("subject for %s", event).isNotBlank();
            assertThat(content.htmlBody()).as("html for %s", event).isNotBlank();
            assertThat(content.textBody()).as("text for %s", event).isNotBlank();
        }
    }

    @Test
    void theVerificationDefaultCarriesTheCodeAndTtl() {
        OutboundEmail email = render(EmailEvent.EMAIL_VERIFICATION_CODE,
                Map.of("code", "123456", "ttlMinutes", 10), null);
        assertThat(email.textBody()).contains("123456").contains("10 minutes");
        assertThat(email.htmlBody()).contains("123456");
    }

    @Test
    void theInvitationDefaultCarriesTheSetPasswordLink() {
        OutboundEmail email = render(EmailEvent.ONBOARDING_INVITATION,
                Map.of("workspaceUrl", "https://acme.example", "setPasswordUrl", "https://acme.example/set?token=x",
                        "slug", "acme"), null);
        // The plain-text body carries the link verbatim; the HTML href carries it too (the `=` is HTML-entity
        // encoded — the mail client decodes it — so assert the token-bearing path, not the raw `=`).
        assertThat(email.textBody()).contains("https://acme.example/set?token=x");
        assertThat(email.htmlBody()).contains("https://acme.example/set?token");
    }

    @Test
    void theSignupDefaultCarriesTheActivateLink() {
        OutboundEmail email = render(EmailEvent.SIGNUP_VERIFICATION,
                Map.of("activateUrl", "https://acme.example/activate?token=x", "slug", "acme"), null);
        assertThat(email.textBody()).contains("https://acme.example/activate?token=x");
        assertThat(email.htmlBody()).contains("https://acme.example/activate?token");
    }

    @Test
    void theLogoHeaderAppearsOnlyWhenALogoUrlIsProvided() {
        Map<String, Object> vars = Map.of("code", "123456", "ttlMinutes", 10);

        assertThat(render(EmailEvent.EMAIL_VERIFICATION_CODE, vars, null).htmlBody())
                .doesNotContain("<img");
        assertThat(render(EmailEvent.EMAIL_VERIFICATION_CODE, vars, "https://cdn.example/logo.png").htmlBody())
                .contains("<img").contains("https://cdn.example/logo.png");
    }
}
