package com.example.sso.resource;

import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.resource.internal.catalog.application.ResourceAdminService;
import com.example.sso.resource.internal.catalog.application.ResourceTypeView;
import com.example.sso.resource.internal.domain.MemberType;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves resource-type management is now per-tenant (V82): a tenant admin, acting in its own org, may create
 * its OWN types (the "tenant admin can't manage resources" blocker was {@code createType}'s former
 * {@code requireUnscoped()} platform-only gate). Each created type is org-stamped, RLS-isolated from other
 * tenants, and only its owner may delete it; the shared global vocabulary stays usable by everyone. NOT
 * {@code @Transactional} — the service calls run in their own transactions inside the org scope so the RLS
 * writes and the isolation reads are honest against the real connection.
 */
class ResourceTypeTenantScopeIT extends AbstractIntegrationTest {

    @Autowired
    ResourceAdminService resources;
    @Autowired
    OrganizationService organizations;
    @Autowired
    OrgContext orgContext;

    private final List<Runnable> cleanups = new java.util.ArrayList<>();

    @AfterEach
    void cleanup() {
        for (int i = cleanups.size() - 1; i >= 0; i--) {
            cleanups.get(i).run();
        }
        cleanups.clear();
    }

    @Test
    void aTenantAdminCreatesItsOwnTypeStampedWithItsOrg() {
        UUID orgA = newOrg("rt-a");

        // The former blocker: this ran requireUnscoped() and 403'd for a tenant admin. Now it succeeds and the
        // type is stamped with the acting tenant — proving the org context is bound (a null stamp would mean an
        // unbound context leaking the type as global).
        UUID typeId = orgContext.callInOrg(orgA, () ->
                UUID.fromString(createType("BRANCH_A", Set.of(MemberType.USER)).id()));
        cleanupType(typeId);

        UUID stamped = ownerJdbc().queryForObject(
                "select org_id from resource_type where id = ?", UUID.class, typeId);
        assertThat(stamped).isEqualTo(orgA);
    }

    @Test
    void aPlatformSuperAdminStillCreatesGlobalSharedTypes() {
        UUID typeId = orgContext.callAsPlatform(() ->
                UUID.fromString(createType("GLOBAL_T", Set.of(MemberType.GROUP)).id()));
        cleanupType(typeId);

        UUID stamped = ownerJdbc().queryForObject(
                "select org_id from resource_type where id = ?", UUID.class, typeId);
        assertThat(stamped).isNull(); // a global/shared type
    }

    @Test
    void aTenantsTypeIsInvisibleAndUndeletableToAnotherTenant() {
        UUID orgA = newOrg("rt-iso-a");
        UUID orgB = newOrg("rt-iso-b");
        UUID branchA = orgContext.callInOrg(orgA, () ->
                UUID.fromString(createType("BRANCH_ISO", Set.of(MemberType.USER)).id()));
        cleanupType(branchA);

        // Tenant B never sees tenant A's type in its listing (RLS), and cannot delete it — a non-revealing 404,
        // not a 403 that would confirm the type exists.
        List<String> visibleToB = orgContext.callInOrg(orgB,
                () -> resources.listTypes().stream().map(ResourceTypeView::name).toList());
        assertThat(visibleToB).doesNotContain("BRANCH_ISO");

        assertThatThrownBy(() -> orgContext.callInOrg(orgB, () -> {
            resources.deleteType(branchA);
            return null;
        })).isInstanceOf(NotFoundException.class);

        // A creates a resource on its OWN type (tier-first resolution); B cannot resolve A's type by name.
        UUID resourceA = orgContext.callInOrg(orgA, () ->
                UUID.fromString(resources.create("branch-a-hq", "BRANCH_ISO").id()));
        cleanupResource(resourceA);
        assertThatThrownBy(() -> orgContext.callInOrg(orgB, () -> resources.create("x", "BRANCH_ISO")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void aTenantMayBuildOnTheGlobalVocabulary() {
        UUID orgA = newOrg("rt-global-use");
        // A global/shared type (created here as platform so the test is self-contained); a tenant may build a
        // resource on it — tier-first resolution falls back to the global type.
        String globalType = "GLOBAL_SHARED_" + suffix();
        UUID typeId = orgContext.callAsPlatform(() ->
                UUID.fromString(createType(globalType, Set.of(MemberType.USER)).id()));
        cleanupType(typeId);

        UUID resource = orgContext.callInOrg(orgA, () ->
                UUID.fromString(resources.create("hq", globalType).id()));
        cleanupResource(resource);

        UUID stamped = ownerJdbc().queryForObject(
                "select org_id from resource where id = ?", UUID.class, resource);
        assertThat(stamped).isEqualTo(orgA); // the resource is the tenant's, built on the global type
    }

    @Test
    void deletingAGlobalTypeInUseByAnotherTenantIsACleanConflictNotA500() {
        UUID orgA = newOrg("rt-inuse");
        String gType = "GLOBAL_INUSE_" + suffix();
        UUID typeId = orgContext.callAsPlatform(() ->
                UUID.fromString(createType(gType, Set.of(MemberType.USER)).id()));
        cleanupType(typeId);
        UUID resource = orgContext.callInOrg(orgA, () ->
                UUID.fromString(resources.create("uses-global", gType).id()));
        cleanupResource(resource);

        // An un-drilled super deletes the global type a tenant's resource uses. The in-use check runs RLS-blind
        // (callAsPlatform), so it SEES the tenant resource and rejects with a clean 409 — not a FK-driven 500.
        assertThatThrownBy(() -> orgContext.callAsPlatform(() -> {
            resources.deleteType(typeId);
            return null;
        })).isInstanceOf(ConflictException.class);
    }

    private ResourceTypeView createType(String name, Set<MemberType> members) {
        return resources.createType(name, members);
    }

    private UUID newOrg(String prefix) {
        UUID id = organizations.create(new NewOrganization(prefix + "-" + suffix(), prefix)).id();
        cleanups.add(() -> ownerJdbc().update("delete from organization where id = ?", id));
        return id;
    }

    private void cleanupType(UUID typeId) {
        cleanups.add(() -> ownerJdbc().update("delete from resource_type where id = ?", typeId));
    }

    private void cleanupResource(UUID resourceId) {
        cleanups.add(() -> ownerJdbc().update("delete from resource where id = ?", resourceId));
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
