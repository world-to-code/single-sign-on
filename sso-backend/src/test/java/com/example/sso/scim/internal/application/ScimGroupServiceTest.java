package com.example.sso.scim.internal.application;

import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.role.RoleRef;
import com.example.sso.user.role.RoleService;
import com.example.sso.user.role.Roles;
import de.captaingoldfish.scim.sdk.common.exceptions.BadRequestException;
import de.captaingoldfish.scim.sdk.common.exceptions.ForbiddenException;
import de.captaingoldfish.scim.sdk.common.resources.Group;
import de.captaingoldfish.scim.sdk.common.resources.multicomplex.Member;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
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
    @Mock
    private ScimMembershipDenyAuditor denyAuditor;

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

    /**
     * The wiring, asserted rather than assumed — its absence is the exact shape of the bug this control was
     * added for. The console's ceiling shipped with a method no production code called: it read as a control
     * and protected nothing. A collaborator that is only ever constructed is the same defect.
     */
    @Test
    void updateNamesWhoTheReplaceTakesOffTheRoleBeforeWritingIt() {
        UUID id = UUID.randomUUID();
        UUID staying = UUID.randomUUID();
        UUID leaving = UUID.randomUUID();
        RoleRef role = mock(RoleRef.class);
        when(role.getId()).thenReturn(id);
        when(role.getName()).thenReturn("engineers");
        when(roleService.findById(id)).thenReturn(Optional.of(role));
        when(roleService.memberIds(id)).thenReturn(Set.of(staying, leaving));

        service.update(Group.builder().id(id.toString()).displayName("engineers")
                .members(List.of(Member.builder().value(staying.toString()).build())).build());

        // The ORDER is the finding, not just the call: read the members BEFORE the write (afterwards only the
        // survivors are left, so the lift would be recorded as affecting nobody), and record AFTER it (the
        // audit row commits in its own transaction, so noting it first asserts a lift a refusal could undo).
        InOrder order = inOrder(roleService, denyAuditor);
        order.verify(roleService).memberIds(id);
        order.verify(roleService).setMembers(id, Set.of(staying));
        order.verify(denyAuditor).noteLiftedBy("SCIM group update", id, "engineers", Set.of(leaving));
    }

    /** Deleting the role lifts for EVERY member at once, and had no test at all — the wiring could be deleted. */
    @Test
    void deleteNamesEveryMemberItTakesOffTheRole() {
        UUID id = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        RoleRef role = mock(RoleRef.class);
        when(role.getId()).thenReturn(id);
        when(role.getName()).thenReturn("engineers");
        when(roleService.findById(id)).thenReturn(Optional.of(role));
        when(roleService.memberIds(id)).thenReturn(Set.of(first, second));

        service.delete(id.toString());

        InOrder order = inOrder(roleService, denyAuditor);
        order.verify(roleService).deleteRole(id);
        order.verify(denyAuditor).noteLiftedBy("SCIM group delete", id, "engineers", Set.of(first, second));
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
