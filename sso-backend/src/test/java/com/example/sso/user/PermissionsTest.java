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
}
