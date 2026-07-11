package com.example.sso.resource;

import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.resource.internal.api.ResourceAdminController;
import com.example.sso.resource.internal.api.ResourceRequest;
import com.example.sso.resource.internal.catalog.application.ResourceTypeSeeder;
import com.example.sso.resource.internal.catalog.application.ResourceTypeView;
import com.example.sso.resource.internal.catalog.application.ResourceView;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.Permissions;
import com.example.sso.user.Roles;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A tenant admin OWNS their org's resource tree. Resource TYPES are a global, super-only vocabulary, so
 * without a seeded baseline a tenant admin holding {@code resource:create} still could not create a single
 * resource ("Resource type not found") — the tree was unusable from inside a tenant. This pins the seeded
 * vocabulary and the tenant admin's ability to build their own tree with it.
 */
class TenantResourceManagementIT extends AbstractIntegrationTest {

    @Autowired
    ResourceAdminController controller;
    @Autowired
    OrganizationService organizations;
    @Autowired
    OrgContext orgContext;
    @Autowired
    ResourceTypeSeeder typeSeeder;

    /**
     * The seeder runs once at boot (it is an {@link org.springframework.boot.ApplicationRunner}), but sibling
     * resource ITs sharing this context wipe {@code resource_type} in their teardown. Re-running it here is
     * both the restore and a proof that seeding is idempotent.
     */
    @BeforeEach
    void ensureBaselineTypes() {
        typeSeeder.run(null);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void theBaselineResourceTypeVocabularyIsSeeded() {
        actAs(Permissions.RESOURCE_READ);

        assertThat(controller.listTypes()).extracting(ResourceTypeView::name)
                .contains("BRANCH", "DEPARTMENT", "TEAM");
    }

    @Test
    void aTenantAdminBuildsTheirOwnOrgsResourceTreeFromTheSeededTypes() {
        UUID orgId = organizations.create(new NewOrganization(
                "res-it-" + UUID.randomUUID().toString().substring(0, 8), "Res IT")).id();
        actAs(Roles.ORG_ADMIN, Permissions.RESOURCE_CREATE, Permissions.RESOURCE_READ,
                Permissions.RESOURCE_UPDATE);

        // Create a branch, then a department under it — the whole structure a tenant admin needs.
        ResourceView branch = orgContext.callInOrg(orgId,
                () -> controller.create(new ResourceRequest("Seoul", "BRANCH")).getBody());
        ResourceView department = orgContext.callInOrg(orgId,
                () -> controller.createSubResource(UUID.fromString(branch.id()),
                        new ResourceRequest("Engineering", "DEPARTMENT")).getBody());
        orgContext.runInOrg(orgId, () ->
                controller.rename(UUID.fromString(department.id()), new ResourceRequest("R&D", null)));

        List<ResourceView> tree = orgContext.callInOrg(orgId, controller::list);
        assertThat(tree).extracting(ResourceView::name).contains("Seoul", "R&D");
        // Both nodes are stamped with the tenant's org — a tenant admin never creates a global resource.
        assertThat(orgContext.callInOrg(orgId, () -> controller.get(UUID.fromString(branch.id())).name()))
                .isEqualTo("Seoul");
    }

    private void actAs(String... authorities) {
        List<SimpleGrantedAuthority> granted = Arrays.stream(authorities)
                .map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("tenantadmin", null, granted));
    }
}
