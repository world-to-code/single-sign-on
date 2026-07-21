package com.example.sso.metadata;

import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Profile mappings against a real database.
 *
 * <p>The cross-tenant guards here were only ever exercised through mocks, which cannot hold what the database
 * holds — RLS, the unique indexes, the retarget-in-place flush ordering. A mapping decides which values an
 * identity source writes onto a tenant's attributes, and those attributes grant roles, so a mapping pointing
 * across a tenant boundary is a authorization crossing, not a data-modelling slip.
 */
class ProfileMappingIsolationIT extends AbstractIntegrationTest {

    @Autowired ProfileMappingService mappings;
    @Autowired ProfileService profiles;
    @Autowired OrganizationService organizations;
    @Autowired OrgContext orgContext;

    private UUID orgA;
    private UUID orgB;

    @AfterEach
    void tearDown() {
        if (orgA != null) {
            organizations.delete(orgA);
        }
        if (orgB != null) {
            organizations.delete(orgB);
        }
    }

    private UUID org() {
        String slug = "map-iso-" + UUID.randomUUID().toString().substring(0, 8);
        UUID id = organizations.create(new NewOrganization(slug, slug)).id();
        await().until(() -> orgContext.callInOrg(id, () -> profiles.list()).size() >= 2);
        return id;
    }

    private Profile of(UUID orgId, ProfileKind kind) {
        return orgContext.callInOrg(orgId, () -> profiles.list()).stream()
                .filter(p -> p.kind() == kind).findFirst().orElseThrow();
    }

    @Test
    void aTenantCannotUseAnotherTenantsProfileAsTheSource() {
        orgA = org();
        orgB = org();
        Profile theirSource = of(orgB, ProfileKind.SCIM);
        Profile ourTenant = of(orgA, ProfileKind.TENANT);

        assertThatThrownBy(() -> orgContext.runInOrg(orgA,
                () -> mappings.map(theirSource.id(), "department", ourTenant.id(), "team")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void aTenantCannotFillAnotherTenantsProfile() {
        orgA = org();
        orgB = org();
        Profile ourSource = of(orgA, ProfileKind.SCIM);
        Profile theirTenant = of(orgB, ProfileKind.TENANT);

        assertThatThrownBy(() -> orgContext.runInOrg(orgA,
                () -> mappings.map(ourSource.id(), "department", theirTenant.id(), "team")))
                .isInstanceOf(NotFoundException.class);
    }

    /** A foreign source profile reads as empty rather than revealing that it exists. */
    @Test
    void aTenantSeesNothingThroughAnotherTenantsProfile() {
        orgA = org();
        orgB = org();
        Profile theirSource = of(orgB, ProfileKind.SCIM);
        Profile theirTenant = of(orgB, ProfileKind.TENANT);
        orgContext.runInOrg(orgB, () -> mappings.map(theirSource.id(), "department", theirTenant.id(), "team"));

        assertThat(orgContext.callInOrg(orgA, () -> mappings.mappingsFrom(theirSource.id()))).isEmpty();
    }

    /** Deleting through a foreign id must not delete — the id is client-supplied. */
    @Test
    void aTenantCannotUnmapAnotherTenantsMapping() {
        orgA = org();
        orgB = org();
        Profile theirSource = of(orgB, ProfileKind.SCIM);
        Profile theirTenant = of(orgB, ProfileKind.TENANT);
        ProfileMapping theirs = orgContext.callInOrg(orgB,
                () -> mappings.map(theirSource.id(), "department", theirTenant.id(), "team"));

        orgContext.runInOrg(orgA, () -> mappings.unmap(theirs.id()));

        assertThat(orgContext.callInOrg(orgB, () -> mappings.mappingsFrom(theirSource.id()))).hasSize(1);
    }

    /**
     * Re-aiming an existing mapping is an update in place. Hibernate flushes inserts before deletes, so a
     * delete-then-insert would hit uq_profile_mapping_source while the old row was still there — a constraint
     * only a real database can enforce, and only a real transaction can trip.
     */
    @Test
    void reAimingASourceReplacesTheMappingRatherThanDuplicatingIt() {
        orgA = org();
        Profile source = of(orgA, ProfileKind.SCIM);
        Profile tenant = of(orgA, ProfileKind.TENANT);
        orgContext.runInOrg(orgA, () -> mappings.map(source.id(), "department", tenant.id(), "team"));

        orgContext.runInOrg(orgA, () -> mappings.map(source.id(), "department", tenant.id(), "division"));

        List<ProfileMapping> after = orgContext.callInOrg(orgA, () -> mappings.mappingsFrom(source.id()));
        assertThat(after).hasSize(1);
        assertThat(after.getFirst().targetKey()).isEqualTo("division");
    }
}
