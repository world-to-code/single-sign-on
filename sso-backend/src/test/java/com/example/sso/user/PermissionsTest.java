package com.example.sso.user;

import com.example.sso.user.rbac.Permissions;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for the pure {@link Permissions#expandImplied} rule: any mutating action on a resource
 * that HAS a catalog {@code read} permission also grants that read, while reads and unknown resources
 * are left untouched. Table-driven, no collaborators — asserts on the resulting set.
 */
class PermissionsTest {

    @Test
    void mutatingActionImpliesReadOnTheSameResource() {
        assertThat(Permissions.expandImplied(Set.of(Permissions.USER_CREATE)))
                .contains(Permissions.USER_CREATE, Permissions.USER_READ);
        assertThat(Permissions.expandImplied(Set.of(Permissions.CLIENT_DELETE)))
                .contains(Permissions.CLIENT_DELETE, Permissions.CLIENT_READ);
    }

    @Test
    void everyMutatingActionAcrossResourcesGainsItsRead() {
        for (String granted : Permissions.ALL) {
            int sep = granted.indexOf(':');
            if (granted.indexOf(':', sep + 1) != -1) {
                continue; // a sub-scoped perm (e.g. audit:read:<category>) is a read, not a resource:action
            }
            String read = granted.substring(0, sep) + ":read";
            if (Permissions.ALL.contains(read) && !granted.endsWith(":read")) {
                assertThat(Permissions.expandImplied(Set.of(granted))).contains(read);
            }
        }
    }

    @Test
    void mutatingActionsAreMutuallyIndependentAndOnlyImplyRead() {
        // The permission "hierarchy" is exactly: create/update/delete each REQUIRE (imply) read, and are
        // otherwise INDEPENDENT of one another — delete does NOT imply update or create, update does not
        // imply create. Locking this guards a real escalation: user:create is deliberately super-only
        // (excluded from ROLE_GROUP_ADMIN), so a holder of user:delete/user:update must never gain
        // user:create through implication.
        assertThat(Permissions.expandImplied(Set.of(Permissions.USER_DELETE)))
                .containsExactlyInAnyOrder(Permissions.USER_DELETE, Permissions.USER_READ)
                .doesNotContain(Permissions.USER_UPDATE, Permissions.USER_CREATE);
        assertThat(Permissions.expandImplied(Set.of(Permissions.USER_UPDATE)))
                .containsExactlyInAnyOrder(Permissions.USER_UPDATE, Permissions.USER_READ)
                .doesNotContain(Permissions.USER_CREATE, Permissions.USER_DELETE);
    }

    @Test
    void resourceTypeManagementImpliesResourceReadOnly() {
        assertThat(Permissions.expandImplied(Set.of(Permissions.RESOURCE_CREATE_TYPE)))
                .containsExactlyInAnyOrder(Permissions.RESOURCE_CREATE_TYPE, Permissions.RESOURCE_READ);
        assertThat(Permissions.expandImplied(Set.of(Permissions.RESOURCE_DELETE_TYPE)))
                .containsExactlyInAnyOrder(Permissions.RESOURCE_DELETE_TYPE, Permissions.RESOURCE_READ);
    }

    @Test
    void resourceTypeManagementIsCatalogedAndTenantGrantable() {
        assertThat(Permissions.ALL).contains(Permissions.RESOURCE_CREATE_TYPE, Permissions.RESOURCE_DELETE_TYPE);
        assertThat(Permissions.isPlatform(Permissions.RESOURCE_CREATE_TYPE)).isFalse();
        assertThat(Permissions.isPlatform(Permissions.RESOURCE_DELETE_TYPE)).isFalse();
        assertThat(Permissions.tenantGrantable())
                .contains(Permissions.RESOURCE_CREATE_TYPE, Permissions.RESOURCE_DELETE_TYPE);
    }

    @Test
    void auditReadMacroExpandsToEveryCategoryAndPii() {
        assertThat(Permissions.expandImplied(Set.of(Permissions.AUDIT_READ)))
                .contains(Permissions.AUDIT_READ,
                        Permissions.AUDIT_READ_AUTHENTICATION, Permissions.AUDIT_READ_AUTHORIZATION,
                        Permissions.AUDIT_READ_SESSION, Permissions.AUDIT_READ_ACCESS,
                        Permissions.AUDIT_READ_APP_ACCESS, Permissions.AUDIT_READ_USER_ACTION,
                        Permissions.AUDIT_READ_ADMIN, Permissions.AUDIT_READ_SYSTEM, Permissions.AUDIT_READ_PII);
    }

    @Test
    void aLoneCategoryGrantIsNotWidenedToOtherCategoriesTheMacroOrPii() {
        // CRITICAL: audit:read:session must NOT imply the broad audit:read (which would explode via the macro
        // into every category + PII). The two-colon shape is guarded out of the resource:read implication.
        assertThat(Permissions.expandImplied(Set.of(Permissions.AUDIT_READ_SESSION)))
                .containsExactly(Permissions.AUDIT_READ_SESSION)
                .doesNotContain(Permissions.AUDIT_READ, Permissions.AUDIT_READ_ADMIN,
                        Permissions.AUDIT_READ_AUTHENTICATION, Permissions.AUDIT_READ_PII);
    }

    @Test
    void categoryAuditPermsAreCatalogedAndTenantGrantable() {
        assertThat(Permissions.ALL).contains(Permissions.AUDIT_READ_SESSION, Permissions.AUDIT_READ_ADMIN,
                Permissions.AUDIT_READ_PII);
        assertThat(Permissions.isPlatform(Permissions.AUDIT_READ_SESSION)).isFalse();
        assertThat(Permissions.isPlatform(Permissions.AUDIT_READ_PII)).isFalse();
        assertThat(Permissions.tenantGrantable())
                .contains(Permissions.AUDIT_READ_SESSION, Permissions.AUDIT_READ_PII);
    }

    @Test
    void expandImpliedIsIdempotent() {
        Set<String> once = Permissions.expandImplied(Set.of(
                Permissions.USER_DELETE, Permissions.RESOURCE_CREATE_TYPE, Permissions.KEY_ROTATE));
        assertThat(Permissions.expandImplied(once)).containsExactlyInAnyOrderElementsOf(once);
    }

    @Test
    void aReadPermissionExpandsToItselfOnly() {
        assertThat(Permissions.expandImplied(Set.of(Permissions.USER_READ)))
                .containsExactly(Permissions.USER_READ);
    }

    @Test
    void singleActionResourcesWithoutAReadAreNotGivenOne() {
        // key:rotate / scim:manage / audit:read have no sibling <resource>:read except audit itself.
        assertThat(Permissions.expandImplied(Set.of(Permissions.KEY_ROTATE)))
                .containsExactly(Permissions.KEY_ROTATE);
        assertThat(Permissions.expandImplied(Set.of(Permissions.SCIM_MANAGE)))
                .containsExactly(Permissions.SCIM_MANAGE);
    }

    @Test
    void nonPermissionShapedStringsArePreservedButNeverExpanded() {
        assertThat(Permissions.expandImplied(List.of("MFA_COMPLETE", "not-a-perm")))
                .containsExactlyInAnyOrder("MFA_COMPLETE", "not-a-perm");
    }

    @Test
    void anEmptyGrantExpandsToEmpty() {
        assertThat(Permissions.expandImplied(Set.of())).isEmpty();
    }

    @Test
    void platformCoversRegistryConsoleAndSharedInfraAndIsASubsetOfTheCatalog() {
        assertThat(Permissions.ALL).containsAll(Permissions.PLATFORM);
        // Platform is now ONLY the tenant registry — org create/update/delete.
        assertThat(Permissions.PLATFORM).contains(
                Permissions.ORG_CREATE, Permissions.ORG_UPDATE, Permissions.ORG_DELETE);
        // the directory + policy + application domain a tenant admin owns is NOT platform; SAML/OIDC apps, app
        // assignments, the resource DAG, SCIM provisioning (/Users), the per-tenant admin-console policy
        // (portal-settings) and the org-scoped audit log read are all org-scoped
        assertThat(Permissions.PLATFORM).doesNotContain(
                Permissions.USER_READ, Permissions.GROUP_CREATE, Permissions.ROLE_CREATE,
                Permissions.POLICY_READ, Permissions.SESSION_POLICY_READ, Permissions.NETWORK_ZONE_READ,
                Permissions.SAML_CREATE, Permissions.CLIENT_CREATE, Permissions.APP_ASSIGNMENT_ASSIGN,
                Permissions.RESOURCE_ASSIGN_ADMIN, Permissions.RESOURCE_CREATE, Permissions.ORG_READ,
                Permissions.ORG_MEMBER_MANAGE, Permissions.SCIM_MANAGE, Permissions.PORTAL_SETTINGS_UPDATE,
                Permissions.AUDIT_READ);
    }

    @Test
    void isPlatformFlagsOnlyPlatformPermissions() {
        assertThat(Permissions.isPlatform(Permissions.ORG_CREATE)).isTrue();
        assertThat(Permissions.isPlatform(Permissions.ORG_DELETE)).isTrue();
        // a tenant's own directory + apps + registry membership + admin-console policy + own audit are tenant-grantable
        assertThat(Permissions.isPlatform(Permissions.AUDIT_READ)).isFalse(); // org-scoped audit read
        assertThat(Permissions.isPlatform(Permissions.PORTAL_SETTINGS_UPDATE)).isFalse(); // per-tenant policy
        assertThat(Permissions.isPlatform(Permissions.CLIENT_CREATE)).isFalse(); // host-org-scoped OIDC clients
        assertThat(Permissions.isPlatform(Permissions.SCIM_MANAGE)).isFalse(); // per-tenant SCIM /Users provisioning
        assertThat(Permissions.isPlatform(Permissions.KEY_ROTATE)).isFalse();  // per-tenant signing keys
        assertThat(Permissions.isPlatform(Permissions.SAML_CREATE)).isFalse(); // per-tenant SAML relying parties
        assertThat(Permissions.isPlatform(Permissions.RESOURCE_CREATE)).isFalse(); // org-scoped resource DAG
        assertThat(Permissions.isPlatform(Permissions.ORG_READ)).isFalse();
        assertThat(Permissions.isPlatform(Permissions.ORG_MEMBER_MANAGE)).isFalse();
        assertThat(Permissions.isPlatform(Permissions.USER_READ)).isFalse();
        assertThat(Permissions.isPlatform(Permissions.ROLE_CREATE)).isFalse();
    }

    @Test
    void tenantGrantableIsTheCatalogMinusPlatform() {
        assertThat(Permissions.tenantGrantable())
                .doesNotContainAnyElementsOf(Permissions.PLATFORM)
                .contains(Permissions.USER_READ, Permissions.ROLE_CREATE, Permissions.GROUP_CREATE,
                        Permissions.POLICY_READ, Permissions.ORG_READ, Permissions.ORG_MEMBER_MANAGE,
                        Permissions.CLIENT_CREATE, // host-org-scoped OIDC clients are tenant-grantable
                        Permissions.KEY_ROTATE, // per-tenant signing keys are tenant-grantable
                        Permissions.SAML_CREATE, // per-tenant SAML relying parties are tenant-grantable
                        Permissions.SCIM_MANAGE, // per-tenant SCIM /Users provisioning is tenant-grantable
                        Permissions.PORTAL_SETTINGS_UPDATE, // per-tenant admin-console elevation policy
                        Permissions.AUDIT_READ) // org-scoped audit log read
                .doesNotContain(Permissions.ORG_CREATE, Permissions.ORG_UPDATE, Permissions.ORG_DELETE)
                .hasSize(Permissions.ALL.size() - Permissions.PLATFORM.size());
    }

    @Test
    void noTenantPermissionImpliesAPlatformRead() {
        // expandImplied only ever synthesizes a <resource>:read for the SAME resource; a tenant-grantable
        // mutating perm must never manufacture a platform read. PLATFORM is now only org create/update/delete
        // (no :read at all), so nothing can imply a platform permission.
        for (String perm : Permissions.tenantGrantable()) {
            assertThat(Permissions.expandImplied(Set.of(perm)))
                    .noneMatch(Permissions::isPlatform);
        }
    }
}
