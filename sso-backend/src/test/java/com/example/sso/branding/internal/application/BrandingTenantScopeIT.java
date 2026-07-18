package com.example.sso.branding.internal.application;

import com.example.sso.branding.Branding;
import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.organization.OrganizationView;
import com.example.sso.security.HostOrgResolver;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Application-layer tenant scoping on real Postgres (RLS through the service's own transactions): a tenant
 * brands in isolation; another tenant resolves the built-in default; and the PUBLIC read path — the org
 * resolved from the request host then read under that org's context — returns only that host's tenant's
 * branding. Mirrors the SMTP/email scope ITs plus the host-derivation the public endpoint relies on.
 */
class BrandingTenantScopeIT extends AbstractIntegrationTest {

    @Autowired
    BrandingService branding;
    @Autowired
    OrgContext orgContext;
    @Autowired
    OrganizationService organizations;
    @Autowired
    HostOrgResolver hostOrgResolver;

    private UUID orgA;
    private UUID orgB;

    @AfterEach
    void tearDown() {
        ownerJdbc().update("delete from org_branding where org_id is null");
        if (orgA != null) {
            organizations.delete(orgA);
        }
        if (orgB != null) {
            organizations.delete(orgB);
        }
    }

    private OrganizationView org(String prefix) {
        String slug = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        return organizations.create(new NewOrganization(slug, slug));
    }

    private BrandingSpec spec(String productName) {
        return new BrandingSpec("https://cdn.example/l.png", "#123abc", productName);
    }

    /** Simulates the public endpoint: host → org → read under that org's context. */
    private Branding resolveForHost(String host) {
        UUID orgId = hostOrgResolver.resolveOrg(host).orElse(null);
        return orgContext.callInOrg(orgId, () -> branding.resolve(orgId));
    }

    @Test
    void aTenantsBrandingIsUsedForItAndAnotherTenantGetsTheDefault() {
        OrganizationView a = org("brand-a");
        OrganizationView b = org("brand-b");
        orgA = a.id();
        orgB = b.id();
        orgContext.runInOrg(orgA, () -> branding.update(spec("Acme")));

        // Resolve directly (the consent page path — org already bound).
        assertThat(orgContext.callInOrg(orgA, () -> branding.resolve(orgA)).productName()).isEqualTo("Acme");
        assertThat(orgContext.callInOrg(orgB, () -> branding.resolve(orgB)).productName()).isEqualTo("Mini SSO");
        assertThat(orgContext.callInOrg(orgB, () -> branding.get().configured())).isFalse();

        // Resolve via the PUBLIC host path — A's subdomain returns A's branding, B's returns the default, and a
        // bare/unknown host returns the default. A client can only ever read its own subdomain's branding.
        assertThat(resolveForHost(a.slug() + ".localhost").productName()).isEqualTo("Acme");
        assertThat(resolveForHost(b.slug() + ".localhost").productName()).isEqualTo("Mini SSO");
        assertThat(resolveForHost("localhost").productName()).isEqualTo("Mini SSO");
        assertThat(resolveForHost("no-such-tenant.localhost").productName()).isEqualTo("Mini SSO");
    }

    @Test
    void aBoundOrglessNonPlatformCallerCannotWriteTheGlobalDefault() {
        assertThatThrownBy(() -> orgContext.runInOrg(null, () -> branding.update(spec("x"))))
                .isInstanceOf(ForbiddenException.class);
    }
}
