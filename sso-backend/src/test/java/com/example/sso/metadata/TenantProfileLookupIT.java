package com.example.sso.metadata;

import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * {@code tenantProfile()} against a real database — the query nothing ran.
 *
 * <p>Three production paths resolve a tenant's own profile through {@code findByOrgIdAndKind}: attribute
 * definitions, profile mappings, and this service. Every test that wanted one took a different route,
 * filtering {@code list()} in memory, so the query itself was executed by no test and mocked in two. What a
 * mock cannot hold is exactly what matters here: the RLS scoping, and the fact that this returns an
 * {@code Optional} — Spring Data raises rather than emptying it when an organization somehow holds two.
 *
 * <p>The consequence of it silently answering empty is not cosmetic. An empty tenant profile means no declared
 * attributes, so an import validates against nothing and a mapping rule has no target schema.
 */
class TenantProfileLookupIT extends AbstractIntegrationTest {

    @Autowired ProfileService profiles;
    @Autowired OrganizationService organizations;
    @Autowired OrgContext orgContext;

    private UUID orgA;
    private UUID orgB;

    @AfterEach
    void tearDown() {
        for (UUID org : new UUID[] {orgA, orgB}) {
            if (org != null) {
                organizations.delete(org);
            }
        }
        orgA = null;
        orgB = null;
    }

    /**
     * The baseline profile is provisioned off the creating request (AFTER_COMMIT), so a test that reads it
     * immediately is racing the provisioner rather than testing the query.
     */
    private UUID org(String prefix) {
        String slug = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        UUID id = orgContext.callAsPlatform(() -> organizations.create(new NewOrganization(slug, slug)).id());
        // Waited for through list(), deliberately NOT through tenantProfile(): the fixture must not depend on
        // the method under test, or a broken query reads as a fixture timeout instead of a failed assertion.
        await().until(() -> orgContext.callInOrg(id, () -> profiles.list()).stream()
                .anyMatch(profile -> profile.kind() == ProfileKind.TENANT));
        return id;
    }

    /** Provisioned with the organization, so a tenant always has one and it is the org's OWN. */
    @Test
    void aTenantResolvesItsOwnProfile() {
        orgA = org("tp-a");

        Optional<Profile> found = orgContext.callInOrg(orgA, () -> profiles.tenantProfile());

        assertThat(found).isPresent();
        assertThat(found.get().kind()).isEqualTo(ProfileKind.TENANT);
        assertThat(found.get().governsUsers()).isTrue();
    }

    /**
     * Two tenants, two profiles, and the lookup keys on the ACTING org — not on "the first TENANT profile",
     * which is what an unscoped query would return once a second organization exists.
     */
    @Test
    void eachTenantResolvesItsOwnAndNeverTheOthers() {
        orgA = org("tp-a");
        orgB = org("tp-b");

        UUID fromA = orgContext.callInOrg(orgA, () -> profiles.tenantProfile().orElseThrow().id());
        UUID fromB = orgContext.callInOrg(orgB, () -> profiles.tenantProfile().orElseThrow().id());

        assertThat(fromA).isNotEqualTo(fromB);
        // And the answer is stable per org rather than "whichever row came back first".
        assertThat(orgContext.callInOrg(orgA, () -> profiles.tenantProfile().orElseThrow().id()))
                .isEqualTo(fromA);
    }

    /** No organization bound is the PLATFORM tier, which owns no tenant profile — empty, not another org's. */
    @Test
    void thePlatformTierResolvesNoTenantProfile() {
        orgA = org("tp-plat");

        assertThat(orgContext.callAsPlatform(() -> profiles.tenantProfile())).isEmpty();
    }

    /**
     * The profile the import and the mapping rules actually use is this one — so if the query ever answered
     * empty, an import would validate against no declarations at all.
     */
    @Test
    void theResolvedProfileIsTheOneThatCarriesTheTenantsDeclarations() {
        orgA = org("tp-decl");

        UUID viaQuery = orgContext.callInOrg(orgA, () -> profiles.tenantProfile().orElseThrow().id());
        UUID viaList = orgContext.callInOrg(orgA, () -> profiles.list().stream()
                .filter(profile -> profile.kind() == ProfileKind.TENANT)
                .findFirst().orElseThrow().id());

        assertThat(viaQuery).isEqualTo(viaList);
    }
}
