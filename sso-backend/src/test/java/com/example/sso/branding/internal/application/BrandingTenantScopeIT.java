package com.example.sso.branding.internal.application;

import com.example.sso.branding.Branding;
import com.example.sso.branding.BrandingTheme;
import com.example.sso.branding.BrandingIdentity;
import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.organization.OrganizationView;
import com.example.sso.security.HostOrgResolver;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
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

    /**
     * Creates an org AND waits for its baseline provisioning to finish — the same fix its sibling
     * ScreenCopyTenantScopeIT got in c75219fc, which touched only that one file. Deleting the org while the
     * AFTER_COMMIT @Async provisioner is still writing puts a cascading DELETE against concurrent INSERTs
     * that take their locks in the opposite order.
     */
    private OrganizationView org(String prefix) {
        String slug = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        OrganizationView created = organizations.create(new NewOrganization(slug, slug));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(ownerJdbc().queryForObject("select count(*) from profile where org_id = ?",
                        Integer.class, created.id())).isPositive());
        return created;
    }

    private BrandingSpec spec(String productName) {
        return new BrandingSpec(new BrandingIdentity("https://cdn.example/l.png", null, null, productName),
                new BrandingTheme("#123abc", null, null, null, null, null));
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
        assertThat(orgContext.callInOrg(orgB, () -> branding.resolve(orgB)).productName()).isEqualTo("Svalinn");
        assertThat(orgContext.callInOrg(orgB, () -> branding.get().configured())).isFalse();

        // Resolve via the PUBLIC host path — A's subdomain returns A's branding, B's returns the default, and a
        // bare/unknown host returns the default. A client can only ever read its own subdomain's branding.
        assertThat(resolveForHost(a.slug() + ".localhost").productName()).isEqualTo("Acme");
        assertThat(resolveForHost(b.slug() + ".localhost").productName()).isEqualTo("Svalinn");
        assertThat(resolveForHost("localhost").productName()).isEqualTo("Svalinn");
        assertThat(resolveForHost("no-such-tenant.localhost").productName()).isEqualTo("Svalinn");

        // Now B configures its OWN distinct branding: each host returns strictly its own, never the other's.
        orgContext.runInOrg(orgB, () -> branding.update(spec("Beta")));
        assertThat(resolveForHost(a.slug() + ".localhost").productName()).isEqualTo("Acme");
        assertThat(resolveForHost(b.slug() + ".localhost").productName()).isEqualTo("Beta");
    }

    @Test
    void aBoundOrglessNonPlatformCallerCannotWriteTheGlobalDefault() {
        assertThatThrownBy(() -> orgContext.runInOrg(null, () -> branding.update(spec("x"))))
                .isInstanceOf(ForbiddenException.class);
    }
}
