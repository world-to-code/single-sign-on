package com.example.sso.email.internal.application;

import com.example.sso.email.internal.domain.EmailTemplate;
import com.example.sso.email.internal.domain.EmailTemplateRepository;
import com.example.sso.email.template.EmailEvent;
import com.example.sso.email.template.OutboundEmail;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.tenancy.OrgContext;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Per-tenant email template administration: {@code list} answers the acting tier's own template per event (or
 * the inherited default as a starting point); {@code update}/{@code delete} edit the acting tier's OWN row via
 * the fail-closed {@link #writableOrg} (a bound-but-orgless non-platform caller cannot edit the global default),
 * and {@code preview} renders unsaved content with sample data. The logo URL is enforced https; the body is
 * tenant-authored but only ever rendered logic-less ({@link EmailTemplateRenderer}), never evaluated.
 */
@Service
@RequiredArgsConstructor
public class EmailTemplateService {

    private static final String SAMPLE_SLUG = "acme";

    private final EmailTemplateRepository repository;
    private final DefaultEmailTemplates defaults;
    private final EmailTemplateRenderer renderer;
    private final OrgContext orgContext;

    @Transactional(readOnly = true)
    public List<EmailTemplateView> list() {
        return views();
    }

    private List<EmailTemplateView> views() {
        return Arrays.stream(EmailEvent.values()).map(this::viewOf).toList();
    }

    /** Registers/updates the acting tier's template for one event; returns the refreshed set in the same tx. */
    @Transactional
    public List<EmailTemplateView> update(EmailEvent event, EmailTemplateSpec spec) {
        UUID org = writableOrg();
        validate(spec);
        ownRow(event).ifPresentOrElse(
                row -> row.reconfigure(spec.subject(), spec.htmlBody(), trimToNull(spec.textBody()),
                        trimToNull(spec.logoUrl())),
                () -> repository.save(EmailTemplate.create(org, event, spec.subject(), spec.htmlBody(),
                        trimToNull(spec.textBody()), trimToNull(spec.logoUrl()))));
        return views();
    }

    /** Drops the acting tier's template for one event (reverts to the default); returns the refreshed set. */
    @Transactional
    public List<EmailTemplateView> delete(EmailEvent event) {
        writableOrg();
        ownRow(event).ifPresent(repository::delete);
        return views();
    }

    /** Renders {@code spec} with sample data for the editor's preview, WITHOUT persisting it. */
    @Transactional(readOnly = true)
    public EmailTemplatePreview preview(EmailEvent event, EmailTemplateSpec spec) {
        validate(spec);
        String text = StringUtils.hasText(spec.textBody()) ? spec.textBody() : defaults.forEvent(event).textBody();
        EmailTemplateContent content = new EmailTemplateContent(spec.subject(), spec.htmlBody(), text,
                trimToNull(spec.logoUrl()));
        OutboundEmail rendered = renderer.render("preview@example.com", content, sampleVars(event));
        return new EmailTemplatePreview(rendered.subject(), rendered.htmlBody(), rendered.textBody());
    }

    private EmailTemplateView viewOf(EmailEvent event) {
        Optional<EmailTemplate> own = ownRow(event);
        EmailTemplateContent content = own.map(EmailTemplateContent::of).orElseGet(() -> defaults.forEvent(event));
        return new EmailTemplateView(event, own.isPresent(), content.subject(), content.htmlBody(),
                content.textBody(), content.logoUrl(), variablesOf(event));
    }

    private void validate(EmailTemplateSpec spec) {
        if (StringUtils.hasText(spec.logoUrl())
                && !spec.logoUrl().trim().toLowerCase(Locale.ROOT).startsWith("https://")) {
            throw BadRequestException.of("branding.logoUrl.notHttps");
        }
        // Reject a template that won't compile HERE, so a malformed body can never throw at send time.
        renderer.validateSyntax(spec.subject(), spec.htmlBody(), spec.textBody());
    }

    /** The acting tier's OWN row — the platform tier owns the global (org_id NULL) row, a bound-orgless tenant none. */
    private Optional<EmailTemplate> ownRow(EmailEvent event) {
        UUID org = orgContext.currentOrg().orElse(null);
        if (org != null) {
            return repository.findByOrgIdAndEvent(org, event);
        }
        return orgContext.isPlatform() ? repository.findByOrgIdIsNullAndEvent(event) : Optional.empty();
    }

    /** The acting org for a WRITE. Deny-by-default: a bound-but-orgless non-platform caller can't write global. */
    private UUID writableOrg() {
        UUID org = orgContext.currentOrg().orElse(null);
        if (org == null && !orgContext.isPlatform()) {
            throw ForbiddenException.of("email.template.global.platformOnly");
        }
        return org;
    }

    private Set<String> variablesOf(EmailEvent event) {
        Set<String> variables = new TreeSet<>(event.variables());
        variables.add(EmailEvent.LOGO_URL);
        return variables;
    }

    /** Representative values for a preview render — never real user data. */
    private Map<String, Object> sampleVars(EmailEvent event) {
        return switch (event) {
            case EMAIL_VERIFICATION_CODE -> Map.of("code", "123456", "ttlMinutes", 10);
            case ONBOARDING_INVITATION -> Map.of(
                    "workspaceUrl", "https://" + SAMPLE_SLUG + ".example.com",
                    "setPasswordUrl", "https://" + SAMPLE_SLUG + ".example.com/set-password?token=sample",
                    "slug", SAMPLE_SLUG);
            case SIGNUP_VERIFICATION -> Map.of(
                    "activateUrl", "https://" + SAMPLE_SLUG + ".example.com/activate?token=sample",
                    "slug", SAMPLE_SLUG);
        };
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
