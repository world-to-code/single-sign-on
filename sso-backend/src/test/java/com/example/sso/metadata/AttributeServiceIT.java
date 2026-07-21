package com.example.sso.metadata;

import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The metadata store is org-scoped: a tenant only ever reads/writes its own attributes (RLS), a key may carry
 * SEVERAL values (multi-value) edited via add/removeValue with {@code set} as the replace-the-key convenience, and
 * the predicate lookup is likewise tier-confined. Exercised through the real service inside {@code orgContext}
 * scopes (which bind both the tier and the connection's RLS context).
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

    @Test
    void aTenantsOwnAttributeShadowsAGlobalOneOfTheSameKey() {
        UUID orgA = newOrg("attr-shadow-a");
        UUID orgB = newOrg("attr-shadow-b");
        String userId = UUID.randomUUID().toString();
        orgContext.callAsPlatform(() -> { // a platform-set GLOBAL attribute (org_id null)
            attributes.set(EntityKind.USER, userId, "tier", "global-default");
            return null;
        });
        orgContext.runInOrg(orgA, () -> attributes.set(EntityKind.USER, userId, "tier", "gold")); // A overrides it

        // In A's context the OWN value wins; a tenant with no own attribute sees the global default.
        assertThat(orgContext.callInOrg(orgA, () -> attributes.attributesOf(EntityKind.USER, userId)))
                .extracting(Attribute::value).containsExactly("gold");
        assertThat(orgContext.callInOrg(orgB, () -> attributes.attributesOf(EntityKind.USER, userId)))
                .extracting(Attribute::value).containsExactly("global-default");
    }

    @Test
    void aKeyCarriesMultipleValuesAndAddIsIdempotent() {
        UUID orgA = newOrg("attr-mv");
        String userId = UUID.randomUUID().toString();
        orgContext.runInOrg(orgA, () -> {
            attributes.add(EntityKind.USER, userId, "team", "infra");
            attributes.add(EntityKind.USER, userId, "team", "sre");
            attributes.add(EntityKind.USER, userId, "team", "infra"); // idempotent — no duplicate row, no violation
        });
        assertThat(orgContext.callInOrg(orgA, () -> attributes.attributesOf(EntityKind.USER, userId)))
                .extracting(Attribute::key, Attribute::value)
                .containsExactlyInAnyOrder(tuple("team", "infra"), tuple("team", "sre"));
    }

    @Test
    void setReplacesTheKeysWholeValueSetAndRemoveValueDropsOne() {
        UUID orgA = newOrg("attr-mv-edit");
        String userId = UUID.randomUUID().toString();
        orgContext.runInOrg(orgA, () -> {
            attributes.add(EntityKind.USER, userId, "team", "infra");
            attributes.add(EntityKind.USER, userId, "team", "sre");
        });
        // set collapses the whole key to one value...
        orgContext.runInOrg(orgA, () -> attributes.set(EntityKind.USER, userId, "team", "platform"));
        assertThat(orgContext.callInOrg(orgA, () -> attributes.attributesOf(EntityKind.USER, userId)))
                .extracting(Attribute::value).containsExactly("platform");
        // ...then add two back and remove just one value, leaving the other.
        orgContext.runInOrg(orgA, () -> {
            attributes.add(EntityKind.USER, userId, "team", "infra");
            attributes.removeValue(EntityKind.USER, userId, "team", "platform");
        });
        assertThat(orgContext.callInOrg(orgA, () -> attributes.attributesOf(EntityKind.USER, userId)))
                .extracting(Attribute::value).containsExactly("infra");
    }

    @Test
    void setToAValueAlreadyPresentAmongSeveralKeepsItAndDropsTheRest() {
        UUID orgA = newOrg("attr-set-keep");
        String userId = UUID.randomUUID().toString();
        orgContext.runInOrg(orgA, () -> {
            attributes.add(EntityKind.USER, userId, "team", "infra");
            attributes.add(EntityKind.USER, userId, "team", "sre");
        });
        // set to one of the EXISTING values: the present row is kept (no re-insert → no unique violation), the
        // other value is dropped, so the key collapses to exactly that value.
        orgContext.runInOrg(orgA, () -> attributes.set(EntityKind.USER, userId, "team", "infra"));
        assertThat(orgContext.callInOrg(orgA, () -> attributes.attributesOf(EntityKind.USER, userId)))
                .extracting(Attribute::value).containsExactly("infra");
    }

    @Test
    void removingAnAbsentValueLeavesTheKeysOtherValuesUntouched() {
        UUID orgA = newOrg("attr-rmval-absent");
        String userId = UUID.randomUUID().toString();
        orgContext.runInOrg(orgA, () -> attributes.add(EntityKind.USER, userId, "team", "infra"));
        orgContext.runInOrg(orgA, () -> attributes.removeValue(EntityKind.USER, userId, "team", "sre")); // absent
        assertThat(orgContext.callInOrg(orgA, () -> attributes.attributesOf(EntityKind.USER, userId)))
                .extracting(Attribute::value).containsExactly("infra");
    }

    @Test
    void theInCohortReturnsAMultiValueEntityExactlyOnce() {
        UUID orgA = newOrg("attr-in-dedup");
        String userId = UUID.randomUUID().toString();
        orgContext.runInOrg(orgA, () -> { // the user carries TWO of the listed values
            attributes.add(EntityKind.USER, userId, "team", "infra");
            attributes.add(EntityKind.USER, userId, "team", "sre");
        });
        // A user matching the IN cohort on both values must surface once, not once per matching row (distinct).
        assertThat(orgContext.callInOrg(orgA,
                () -> attributes.entityIdsWithAnyValueInTier(EntityKind.USER, "team", List.of("infra", "sre"))))
                .containsExactly(userId);
    }

    @Test
    void aTenantsOwnValueSetShadowsTheGlobalSetPerKey() {
        UUID orgA = newOrg("attr-mv-shadow-a");
        UUID orgB = newOrg("attr-mv-shadow-b");
        String userId = UUID.randomUUID().toString();
        orgContext.callAsPlatform(() -> { // global team = {a, b}, global region = {eu}
            attributes.add(EntityKind.USER, userId, "team", "a");
            attributes.add(EntityKind.USER, userId, "team", "b");
            attributes.add(EntityKind.USER, userId, "region", "eu");
            return null;
        });
        orgContext.runInOrg(orgA, () -> attributes.add(EntityKind.USER, userId, "team", "c")); // A overrides team only

        // The shadow is PER KEY: A's own team set shadows the global team, but the un-overridden global region is
        // still inherited — a tenant does not lose global keys it never touched.
        assertThat(orgContext.callInOrg(orgA, () -> attributes.attributesOf(EntityKind.USER, userId)))
                .extracting(Attribute::key, Attribute::value)
                .containsExactlyInAnyOrder(tuple("team", "c"), tuple("region", "eu"));
        // B overrides nothing, so it inherits the whole global set of both keys.
        assertThat(orgContext.callInOrg(orgB, () -> attributes.attributesOf(EntityKind.USER, userId)))
                .extracting(Attribute::key, Attribute::value)
                .containsExactlyInAnyOrder(tuple("team", "a"), tuple("team", "b"), tuple("region", "eu"));
    }

    @Test
    void removingAnAbsentAttributeIsANoOp() {
        UUID orgA = newOrg("attr-rm");
        String userId = UUID.randomUUID().toString();

        orgContext.runInOrg(orgA, () -> attributes.remove(EntityKind.USER, userId, "missing")); // must not throw
        assertThat(orgContext.callInOrg(orgA, () -> attributes.attributesOf(EntityKind.USER, userId))).isEmpty();
    }

    private UUID newOrg(String prefix) {
        String s = UUID.randomUUID().toString().substring(0, 8);
        return organizations.create(new NewOrganization(prefix + "-" + s, prefix + " " + s)).id();
    }
    /**
     * The bulk retirement, which is one statement now rather than a SELECT plus a DELETE per row. It is also a
     * security operation — removing an attribute can retract an ABAC-granted role — so the case that matters
     * most is the one where it removes NOTHING and must not look like it worked.
     */
    @Test
    void retiringSeveralKeysRemovesThemAllInOneGo() {
        UUID orgA = newOrg("attr-bulk");
        String userId = UUID.randomUUID().toString();
        orgContext.runInOrg(orgA, () -> {
            attributes.set(EntityKind.USER, userId, "team", "platform");
            attributes.set(EntityKind.USER, userId, "tier", "gold");
            attributes.set(EntityKind.USER, userId, "kept", "yes");
        });

        orgContext.runInOrg(orgA, () -> attributes.removeAll(EntityKind.USER, userId, List.of("team", "tier")));

        assertThat(orgContext.callInOrg(orgA, () -> attributes.attributesOf(EntityKind.USER, userId)))
                .extracting(Attribute::key).containsExactly("kept");
    }

    /** Retiring keys that are not there changes nothing and, crucially, deletes nothing else. */
    @Test
    void retiringKeysThatAreNotThereIsHarmless() {
        UUID orgA = newOrg("attr-none");
        String userId = UUID.randomUUID().toString();
        orgContext.runInOrg(orgA, () -> attributes.set(EntityKind.USER, userId, "kept", "yes"));

        orgContext.runInOrg(orgA, () -> attributes.removeAll(EntityKind.USER, userId, List.of("absent")));

        assertThat(orgContext.callInOrg(orgA, () -> attributes.attributesOf(EntityKind.USER, userId)))
                .extracting(Attribute::key).containsExactly("kept");
    }

    /**
     * A tenant's retirement must not reach the platform tier's rows for the same entity — the delete is now a
     * single statement, so its WHERE clause is the only thing holding that line.
     */
    @Test
    void retiringInATenantLeavesTheGlobalRowAlone() {
        UUID orgA = newOrg("attr-tier");
        String userId = UUID.randomUUID().toString();
        orgContext.runAsPlatform(() -> attributes.set(EntityKind.USER, userId, "clearance", "global"));
        orgContext.runInOrg(orgA, () -> attributes.set(EntityKind.USER, userId, "clearance", "tenant"));

        orgContext.runInOrg(orgA, () -> attributes.removeAll(EntityKind.USER, userId, List.of("clearance")));

        assertThat(orgContext.callAsPlatform(() -> attributes.attributesOfInTier(EntityKind.USER, userId)))
                .extracting(Attribute::value).containsExactly("global");
    }

    /**
     * The group-inheritance read on the authorization path. It used to fetch every tier's rows and filter in
     * memory, which could not use an index that leads with org_id — on the ABAC hot table, on every request.
     */
    @Test
    void theUnionReadSeesOnlyTheActingTiersRows() {
        UUID orgA = newOrg("attr-union");
        String one = UUID.randomUUID().toString();
        String two = UUID.randomUUID().toString();
        orgContext.runAsPlatform(() -> attributes.set(EntityKind.GROUP, one, "scope", "global"));
        orgContext.runInOrg(orgA, () -> {
            attributes.set(EntityKind.GROUP, one, "scope", "tenant");
            attributes.set(EntityKind.GROUP, two, "extra", "yes");
        });

        assertThat(orgContext.callInOrg(orgA,
                () -> attributes.unionAttributesOfInTier(EntityKind.GROUP, List.of(one, two))))
                .extracting(Attribute::value).containsExactlyInAnyOrder("tenant", "yes");
    }
}
