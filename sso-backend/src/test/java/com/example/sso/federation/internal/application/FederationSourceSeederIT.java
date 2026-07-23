package com.example.sso.federation.internal.application;

import com.example.sso.metadata.AttributeDefinition;
import com.example.sso.metadata.AttributeDefinitionService;
import com.example.sso.metadata.AttributeSource;
import com.example.sso.metadata.Profile;
import com.example.sso.metadata.ProfileKind;
import com.example.sso.metadata.ProfileMappingService;
import com.example.sso.metadata.ProfileService;
import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The seeder against a real database: it plants the standard OIDC claim attributes DIRECTORY-owned on the
 * tenant profile and maps them from the tenant's one OIDC source, and — the invariant the unique indexes
 * hold — a second run adds nothing. Runs the seed OUTSIDE the seeding transaction, inside the acting org.
 */
class FederationSourceSeederIT extends AbstractIntegrationTest {

    @Autowired FederationSourceSeeder seeder;
    @Autowired ProfileService profiles;
    @Autowired ProfileMappingService mappings;
    @Autowired AttributeDefinitionService definitions;
    @Autowired OrganizationService organizations;
    @Autowired OrgContext orgContext;

    @Test
    void seedsTheClaimAttributesOnceAndIsIdempotent() {
        UUID org = newOrg();

        orgContext.runInOrg(org, () -> seeder.ensureOidcSource(org));
        orgContext.runInOrg(org, () -> seeder.ensureOidcSource(org)); // second run must add nothing

        orgContext.runInOrg(org, () -> {
            UUID tenant = profiles.tenantProfile().orElseThrow().id();
            List<AttributeDefinition> claims = definitions.definitionsIn(tenant).stream()
                    .filter(d -> List.of("given_name", "family_name", "picture").contains(d.key())).toList();
            assertThat(claims).extracting(AttributeDefinition::key)
                    .containsExactlyInAnyOrder("given_name", "family_name", "picture"); // 3, not 6
            assertThat(claims).allSatisfy(d -> assertThat(d.source()).isEqualTo(AttributeSource.DIRECTORY));

            UUID source = oidcSource(org);
            assertThat(mappings.mappingsFrom(source)).hasSize(3); // idempotent — no duplicate mappings
        });
    }

    /** The tenant's OIDC source profile id — provisionForSource is idempotent, so this resolves the existing one. */
    private UUID oidcSource(UUID org) {
        Profile source = profiles.provisionForSource(org, ProfileKind.OIDC, "OIDC");
        return source.id();
    }

    private UUID newOrg() {
        String slug = "fed-seed-" + UUID.randomUUID().toString().substring(0, 8);
        UUID org = organizations.create(new NewOrganization(slug, slug)).id();
        // The tenant profile is provisioned AFTER_COMMIT/@Async on org creation; the seed needs it present.
        await().until(() -> orgContext.callInOrg(org, () -> profiles.tenantProfile().isPresent()));
        return org;
    }
}
