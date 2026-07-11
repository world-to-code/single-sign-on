package com.example.sso.resource.internal.authorization.application;

import com.example.sso.resource.internal.domain.ResourceRepository;
import com.example.sso.resource.internal.domain.ResourceRoleTier;
import com.example.sso.user.role.Roles;
import com.example.sso.user.group.UserGroupService;
import com.example.sso.user.account.UserService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for the Postgres-graph {@link ResourceScope}. Outside an HTTP request the managed set is
 * computed directly (no memo), and {@code isUnscoped} matches the effective super-admin model (direct
 * ROLE_ADMIN). The managed-set walk must bind the tier NAME, never inline the enum — asserted via
 * {@code verify} on the exact {@code ADMIN} tier argument.
 */
@ExtendWith(MockitoExtension.class)
class ResourceScopeImplTest {

    @Mock
    private ResourceRepository resources;
    @Mock
    private UserService users;
    @Mock
    private UserGroupService userGroups;

    @InjectMocks
    private ResourceScopeImpl scope;

    private final UUID actor = UUID.randomUUID();

    @Test
    void directAdminRoleIsUnscoped() {
        when(users.hasRole(actor, Roles.ADMIN)).thenReturn(true);

        assertThat(scope.isUnscoped(actor)).isTrue();
    }

    @Test
    void aUserWithNoAdminRoleNorAdminGroupIsScoped() {
        when(users.hasRole(actor, Roles.ADMIN)).thenReturn(false);
        when(userGroups.membershipsForUser(actor)).thenReturn(List.of());

        assertThat(scope.isUnscoped(actor)).isFalse();
    }

    @Test
    void managedResourceIdsWalkTheGraphBoundToTheAdminTierName() {
        UUID managed = UUID.randomUUID();
        when(resources.findManagedResourceIds(actor, ResourceRoleTier.ADMIN.name()))
                .thenReturn(Set.of(managed));

        assertThat(scope.managedResourceIds(actor)).containsExactly(managed);
        verify(resources).findManagedResourceIds(actor, ResourceRoleTier.ADMIN.name());
    }

    @Test
    void reachesDelegatesToTheRepository() {
        UUID ancestor = UUID.randomUUID();
        UUID descendant = UUID.randomUUID();
        when(resources.reaches(ancestor, descendant)).thenReturn(true);

        assertThat(scope.reaches(ancestor, descendant)).isTrue();
    }
}
