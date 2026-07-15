package com.example.sso.metadata;

import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The metadata store is org-scoped: a tenant only ever reads/writes its own attributes (RLS), an upsert keeps a
 * single value per key, and the predicate lookup is likewise tier-confined. Exercised through the real service
 * inside {@code orgContext} scopes (which bind both the tier and the connection's RLS context).
 */
class AttributeServiceIT extends AbstractIntegrationTest {

    @Autowired AttributeService attributes;
    @Autowired OrgContext orgContext;
    @Autowired OrganizationService organizations;

    @Test
    void attributesAreOrgScopedAndIsolatedAcrossTenants() {
        UUID orgA = newOrg("attr-a");
        UUID orgB = newOrg("attr-b");
        String userId = UUID.randomUUID().toString();

        orgContext.runInOrg(orgA, () -> attributes.set(EntityKind.USER, userId, "department", "engineering"));

        assertThat(orgContext.callInOrg(orgA, () -> attributes.attributesOf(EntityKind.USER, userId)))
                .extracting(Attribute::key, Attribute::value).containsExactly(tuple("department", "engineering"));
        // Tenant B cannot see tenant A's attribute.
        assertThat(orgContext.callInOrg(orgB, () -> attributes.attributesOf(EntityKind.USER, userId))).isEmpty();
    }

    @Test
    void setUpsertsAndRemoveDeletesWithinTheTier() {
        UUID orgA = newOrg("attr-up");
        String userId = UUID.randomUUID().toString();
        orgContext.runInOrg(orgA, () -> attributes.set(EntityKind.USER, userId, "tier", "gold"));
        orgContext.runInOrg(orgA, () -> attributes.set(EntityKind.USER, userId, "tier", "platinum")); // upsert, not a 2nd row

        assertThat(orgContext.callInOrg(orgA, () -> attributes.attributesOf(EntityKind.USER, userId)))
                .extracting(Attribute::value).containsExactly("platinum");

        orgContext.runInOrg(orgA, () -> attributes.remove(EntityKind.USER, userId, "tier"));
        assertThat(orgContext.callInOrg(orgA, () -> attributes.attributesOf(EntityKind.USER, userId))).isEmpty();
    }

    @Test
    void entityIdsWithFindsMatchingEntitiesInTheActingTierOnly() {
        UUID orgA = newOrg("attr-idx-a");
        UUID orgB = newOrg("attr-idx-b");
        String u1 = UUID.randomUUID().toString();
        String u2 = UUID.randomUUID().toString();
        orgContext.runInOrg(orgA, () -> attributes.set(EntityKind.USER, u1, "dept", "eng"));
        orgContext.runInOrg(orgA, () -> attributes.set(EntityKind.USER, u2, "dept", "eng"));
        orgContext.runInOrg(orgB, () -> attributes.set(EntityKind.USER, UUID.randomUUID().toString(), "dept", "eng"));

        assertThat(orgContext.callInOrg(orgA, () -> attributes.entityIdsWith(EntityKind.USER, "dept", "eng")))
                .containsExactlyInAnyOrder(u1, u2);
        assertThat(orgContext.callInOrg(orgB, () -> attributes.entityIdsWith(EntityKind.USER, "dept", "eng"))).hasSize(1);
    }

    private UUID newOrg(String prefix) {
        String s = UUID.randomUUID().toString().substring(0, 8);
        return organizations.create(new NewOrganization(prefix + "-" + s, prefix + " " + s)).id();
    }
}
