package com.example.sso.user;

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
            String read = granted.substring(0, sep) + ":read";
            if (Permissions.ALL.contains(read) && !granted.endsWith(":read")) {
                assertThat(Permissions.expandImplied(Set.of(granted))).contains(read);
            }
        }
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
        assertThat(Permissions.PLATFORM).contains(
                Permissions.ORG_CREATE, Permissions.PORTAL_SETTINGS_UPDATE,
                Permissions.SCIM_MANAGE, Permissions.AUDIT_READ, Permissions.CLIENT_CREATE);
        // the directory + policy domain a tenant admin owns is NOT platform; SAML RPs, app assignments and
        // the resource DAG are now org-scoped too
        assertThat(Permissions.PLATFORM).doesNotContain(
                Permissions.USER_READ, Permissions.GROUP_CREATE, Permissions.ROLE_CREATE,
                Permissions.POLICY_READ, Permissions.SESSION_POLICY_READ, Permissions.NETWORK_ZONE_READ,
                Permissions.SAML_CREATE, Permissions.APP_ASSIGNMENT_ASSIGN, Permissions.RESOURCE_ASSIGN_ADMIN,
                Permissions.RESOURCE_CREATE, Permissions.ORG_READ, Permissions.ORG_MEMBER_MANAGE);
    }

    @Test
    void isPlatformFlagsOnlyPlatformPermissions() {
        assertThat(Permissions.isPlatform(Permissions.ORG_CREATE)).isTrue();
        assertThat(Permissions.isPlatform(Permissions.PORTAL_SETTINGS_UPDATE)).isTrue();
        assertThat(Permissions.isPlatform(Permissions.SCIM_MANAGE)).isTrue();
        assertThat(Permissions.isPlatform(Permissions.CLIENT_CREATE)).isTrue();
        // a tenant's own directory + registry membership stay tenant-grantable; key:rotate is now per-tenant
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
                        Permissions.KEY_ROTATE, // per-tenant signing keys are tenant-grantable
                        Permissions.SAML_CREATE) // per-tenant SAML relying parties are tenant-grantable
                .doesNotContain(Permissions.CLIENT_CREATE, Permissions.AUDIT_READ)
                .hasSize(Permissions.ALL.size() - Permissions.PLATFORM.size());
    }

    @Test
    void noTenantPermissionImpliesAPlatformRead() {
        // expandImplied only ever synthesizes a <resource>:read for the SAME resource; a tenant-grantable
        // mutating perm must never manufacture a platform read (e.g. portal-settings:read).
        for (String perm : Permissions.tenantGrantable()) {
            assertThat(Permissions.expandImplied(Set.of(perm)))
                    .noneMatch(Permissions::isPlatform);
        }
    }
}
