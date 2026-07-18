package com.example.sso.email.internal.application;

import com.example.sso.email.internal.domain.EmailTemplate;
import com.example.sso.email.internal.domain.EmailTemplateRepository;
import com.example.sso.email.template.EmailComposer;
import com.example.sso.email.template.EmailEvent;
import com.example.sso.email.template.OutboundEmail;
import com.example.sso.tenancy.OrgContext;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Resolves the acting tenant's template for an event (own → platform → built-in default) and renders it via
 * {@link EmailTemplateRenderer}. Runs under the ambient {@link OrgContext} (the async send paths bind the org
 * before calling), so resolution is tenant-scoped and RLS binds on the async thread.
 */
@Service
@RequiredArgsConstructor
class EmailComposerImpl implements EmailComposer {

    private final EmailTemplateRepository repository;
    private final DefaultEmailTemplates defaults;
    private final EmailTemplateRenderer renderer;
    private final OrgContext orgContext;

    @Override
    @Transactional(readOnly = true)
    public OutboundEmail compose(EmailEvent event, String to, Map<String, Object> vars) {
        return renderer.render(to, effectiveContent(event), vars);
    }

    /** The resolved content, with its text alternative filled from the built-in default when the template omits it. */
    private EmailTemplateContent effectiveContent(EmailEvent event) {
        EmailTemplateContent content = resolve(event);
        if (StringUtils.hasText(content.textBody())) {
            return content;
        }
        return new EmailTemplateContent(content.subject(), content.htmlBody(),
                defaults.forEvent(event).textBody(), content.logoUrl());
    }

    /** Own template → platform template → built-in default. A null orgId (global user) resolves only the platform row. */
    private EmailTemplateContent resolve(EmailEvent event) {
        UUID org = orgContext.currentOrg().orElse(null);
        Optional<EmailTemplate> row = org != null
                ? repository.findByOrgIdAndEvent(org, event).or(() -> repository.findByOrgIdIsNullAndEvent(event))
                : repository.findByOrgIdIsNullAndEvent(event);
        return row.map(EmailTemplateContent::of).orElseGet(() -> defaults.forEvent(event));
    }
}
