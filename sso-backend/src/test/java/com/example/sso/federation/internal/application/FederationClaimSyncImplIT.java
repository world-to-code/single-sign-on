package com.example.sso.federation.internal.application;

import com.example.sso.federation.FederationClaimSync;
import com.example.sso.metadata.Attribute;
import com.example.sso.metadata.AttributeService;
import com.example.sso.metadata.EntityKind;
import com.example.sso.metadata.ProfileService;
import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

/**
 * End to end against a real database: with the tenant's OIDC claim attributes seeded, a login's claims are
 * carried onto the user through the mappings — a mapped claim is written, an unmapped one is not. Exercises the
 * real {@code applyFromDirectory} directory-owned write path outside the seeding transaction.
 */
class FederationClaimSyncImplIT extends AbstractIntegrationTest {

    @Autowired FederationClaimSync claimSync;
    @Autowired FederationSourceSeeder seeder;
    @Autowired AttributeService attributes;
    @Autowired ProfileService profiles;
    @Autowired OrganizationService organizations;
    @Autowired OrgContext orgContext;

    @Test
    void carriesMappedClaimsOntoTheUserAndIgnoresUnmappedOnes() {
        UUID org = newOrg();
        orgContext.runInOrg(org, () -> seeder.ensureOidcSource(org)); // plants given_name/family_name/picture + mappings
        String user = UUID.randomUUID().toString();

        claimSync.applyClaims(org, user,
                Map.of("given_name", "Ada", "family_name", "Lovelace", "unmapped", "ignore-me"));

        List<Attribute> attrs = orgContext.callInOrg(org, () -> attributes.attributesOf(EntityKind.USER, user));
        assertThat(attrs).extracting(Attribute::key, Attribute::value)
                .contains(tuple("given_name", "Ada"), tuple("family_name", "Lovelace"));
        assertThat(attrs).extracting(Attribute::key).doesNotContain("unmapped"); // no mapping, no write
    }

    private UUID newOrg() {
        String slug = "fed-claim-" + UUID.randomUUID().toString().substring(0, 8);
        UUID org = organizations.create(new NewOrganization(slug, slug)).id();
        await().until(() -> orgContext.callInOrg(org, () -> profiles.tenantProfile().isPresent()));
        return org;
    }
}
