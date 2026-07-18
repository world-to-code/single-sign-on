package com.example.sso.email.internal.application;

import com.example.sso.email.template.EmailComposer;
import com.example.sso.email.template.EmailEvent;
import com.example.sso.email.template.OutboundEmail;
import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Application-layer tenant scoping on real Postgres (RLS through the service's own transactions): a tenant
 * customizes an event's template in isolation; another tenant neither sees it nor renders with it (it gets the
 * built-in default); and the composer — run under each org's context — resolves the correct template. Mirrors
 * {@code SmtpSettingsTenantScopeIT}.
 */
class EmailTemplateTenantScopeIT extends AbstractIntegrationTest {

    private static final EmailEvent EVENT = EmailEvent.EMAIL_VERIFICATION_CODE;
    private static final Map<String, Object> VARS = Map.of("code", "123456", "ttlMinutes", 10);

    @Autowired
    EmailTemplateService templates;
    @Autowired
    EmailComposer composer;
    @Autowired
    OrgContext orgContext;
    @Autowired
    OrganizationService organizations;

    private UUID orgA;
    private UUID orgB;

    @AfterEach
    void tearDown() {
        ownerJdbc().update("delete from email_template where org_id is null");
        if (orgA != null) {
            organizations.delete(orgA);
        }
        if (orgB != null) {
            organizations.delete(orgB);
        }
    }

    private UUID org(String prefix) {
        String slug = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        return organizations.create(new NewOrganization(slug, slug)).id();
    }

    private EmailTemplateSpec spec(String subject) {
        return new EmailTemplateSpec(subject, "<b>{{code}}</b>", "code {{code}}", null);
    }

    @Test
    void aTenantsTemplateIsUsedForItAndNeverForAnotherTenant() {
        orgA = org("tmpl-a");
        orgB = org("tmpl-b");
        orgContext.runInOrg(orgA, () -> templates.update(EVENT, spec("Acme code {{code}}")));

        // Org A composes with ITS template; org B — with no own row — composes with the built-in default.
        assertThat(orgContext.callInOrg(orgA, this::render).subject()).isEqualTo("Acme code 123456");
        assertThat(orgContext.callInOrg(orgB, this::render).subject())
                .isEqualTo("Verify your email for Mini SSO"); // the built-in default subject
        // And B's admin view shows it inherits the default (not configured), never A's row.
        assertThat(orgContext.callInOrg(orgB, this::configured)).isFalse();
    }

    @Test
    void aBoundOrglessNonPlatformCallerCannotWriteTheGlobalDefault() {
        assertThatThrownBy(() -> orgContext.runInOrg(null, () -> templates.update(EVENT, spec("x {{code}}"))))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void aBoundOrglessNonPlatformCallerDoesNotSeeThePlatformGlobalTemplateAsItsOwn() {
        // The platform configures a global template; a bound-but-orgless NON-platform caller must read NONE of it
        // as "its own" (the read guard is symmetric with the write guard — the SMTP feature's fixed bug).
        orgContext.runAsPlatform(() -> templates.update(EVENT, spec("Global {{code}}")));

        boolean anyConfigured = orgContext.callInOrg(null,
                () -> templates.list().stream().anyMatch(EmailTemplateView::configured));
        assertThat(anyConfigured).isFalse();
    }

    private OutboundEmail render() {
        return composer.compose(EVENT, "to@x.example", VARS);
    }

    private boolean configured() {
        return templates.list().stream().filter(v -> v.event() == EVENT).findFirst().orElseThrow().configured();
    }
}
