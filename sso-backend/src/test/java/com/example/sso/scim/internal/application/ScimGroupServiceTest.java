package com.example.sso.scim.internal.application;

import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.RoleRef;
import com.example.sso.user.RoleService;
import com.example.sso.user.Roles;
import de.captaingoldfish.scim.sdk.common.exceptions.BadRequestException;
import de.captaingoldfish.scim.sdk.common.exceptions.ForbiddenException;
import de.captaingoldfish.scim.sdk.common.resources.Group;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the SCIM Group service's privilege guard: the elevated roles (ROLE_ADMIN,
 * ROLE_GROUP_ADMIN) can never be created/deleted through SCIM. The guard must fire WITHOUT delegating
 * to the {@link RoleService} — asserted with {@code verify(..., never())}. A normal name delegates.
 */
@ExtendWith(MockitoExtension.class)
class ScimGroupServiceTest {

    @Mock
    private RoleService roleService;
    @Mock
    private OrgContext orgContext;

    @InjectMocks
    private ScimGroupService service;

    @BeforeEach
    void globalTokenByDefault() {
        // Most tests exercise the platform (global) token path; a bound org is the tenant-token case below.
        lenient().when(orgContext.currentOrg()).thenReturn(Optional.empty());
    }

    @Test
    void aTenantBoundTokenIsForbiddenFromGroupManagement() {
        // Groups map to roles, and a tenant token could reach GLOBAL roles via RLS — so the whole endpoint is
        // platform-only. A bound org (tenant token) is refused before any role is touched.
        when(orgContext.currentOrg()).thenReturn(Optional.of(UUID.randomUUID()));

        assertThatThrownBy(() -> service.create(Group.builder().displayName("engineers").build()))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.list(1, 50)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.get(UUID.randomUUID().toString())).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.delete(UUID.randomUUID().toString())).isInstanceOf(ForbiddenException.class);
        verify(roleService, never()).findById(any());
        verify(roleService, never()).count();
    }

    @Test
    void updateRejectsASystemRoleWithoutRewritingItsMembers() {
        UUID id = UUID.randomUUID();
        RoleRef systemRole = mock(RoleRef.class);
        when(systemRole.getName()).thenReturn("ROLE_USER");
        when(systemRole.isSystem()).thenReturn(true);
        when(roleService.findById(id)).thenReturn(Optional.of(systemRole));

        assertThatThrownBy(() -> service.update(Group.builder().id(id.toString()).displayName("ROLE_USER").build()))
                .isInstanceOf(BadRequestException.class);
        verify(roleService, never()).setMembers(any(), any());
    }

    @Test
    void deleteOfAnOrdinaryRoleUsesTheSystemGuardedDelete() {
        UUID id = UUID.randomUUID();
        RoleRef role = mock(RoleRef.class);
        when(role.getId()).thenReturn(id);
        when(role.getName()).thenReturn("engineers");
        when(roleService.findById(id)).thenReturn(Optional.of(role));

        service.delete(id.toString());

        verify(roleService).deleteRole(id); // system-guarded (409s ROLE_USER etc.), not the unguarded delete
        verify(roleService, never()).delete(any());
    }

    @Test
    void createRejectsAProtectedRoleWithoutTouchingTheRoleService() {
        Group group = Group.builder().displayName(Roles.ADMIN).build();

        assertThatThrownBy(() -> service.create(group)).isInstanceOf(BadRequestException.class);
        verify(roleService, never()).create(anyString());
        verify(roleService, never()).findByName(anyString());
    }

    @Test
    void deleteRejectsAProtectedRoleWithoutDeleting() {
        UUID id = UUID.randomUUID();
        RoleRef role = mock(RoleRef.class);
        when(role.getName()).thenReturn(Roles.GROUP_ADMIN);
        when(roleService.findById(id)).thenReturn(Optional.of(role));

        assertThatThrownBy(() -> service.delete(id.toString())).isInstanceOf(BadRequestException.class);
        verify(roleService, never()).delete(any());
    }

    @Test
    void createDelegatesForAnOrdinaryGroupName() {
        UUID roleId = UUID.randomUUID();
        RoleRef role = mock(RoleRef.class);
        when(role.getId()).thenReturn(roleId);
        when(role.getName()).thenReturn("engineers");
        Group group = Group.builder().displayName("engineers").build();
        when(roleService.findByName("engineers")).thenReturn(Optional.empty());
        when(roleService.create("engineers")).thenReturn(role);
        when(roleService.members(roleId)).thenReturn(List.of());

        Group result = service.create(group);

        verify(roleService).create("engineers");
        verify(roleService).setMembers(roleId, Set.of());
        assertThat(result.getDisplayName()).contains("engineers");
    }
}
