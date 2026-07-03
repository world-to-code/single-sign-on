package com.example.sso.user.internal.application;

import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.user.RoleRef;
import com.example.sso.user.internal.domain.AppUser;
import com.example.sso.user.internal.domain.AppUserRepository;
import com.example.sso.user.internal.domain.PermissionRepository;
import com.example.sso.user.internal.domain.Role;
import com.example.sso.user.internal.domain.RoleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RoleServiceImpl}: the system/ADMIN-role guards on rename/edit/delete and the
 * reserved-authority-name rejection that stops a role from minting a security-significant authority.
 */
@ExtendWith(MockitoExtension.class)
class RoleServiceImplTest {

    @Mock private RoleRepository roles;
    @Mock private AppUserRepository users;
    @Mock private PermissionRepository permissions;

    @InjectMocks private RoleServiceImpl service;

    private Role systemRole(String name) {
        Role role = new Role(name);
        role.markSystem();
        return role;
    }

    @Test
    void updatingTheAdminRoleIsRejected() {
        UUID id = UUID.randomUUID();
        when(roles.findById(id)).thenReturn(Optional.of(new Role("ROLE_ADMIN")));

        assertThatThrownBy(() -> service.updateRole(id, "ROLE_ADMIN", Set.of()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void renamingASystemRoleIsRejected() {
        UUID id = UUID.randomUUID();
        when(roles.findById(id)).thenReturn(Optional.of(systemRole("ROLE_USER")));

        assertThatThrownBy(() -> service.updateRole(id, "ROLE_RENAMED", Set.of()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void deletingASystemRoleIsRejected() {
        UUID id = UUID.randomUUID();
        when(roles.findById(id)).thenReturn(Optional.of(systemRole("ROLE_USER")));

        assertThatThrownBy(() -> service.deleteRole(id)).isInstanceOf(ConflictException.class);
        verify(roles, never()).delete(any());
    }

    @Test
    void deletingAMissingRoleThrowsNotFound() {
        UUID id = UUID.randomUUID();
        when(roles.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteRole(id)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void aPermissionShapedRoleNameIsRejected() {
        assertThatThrownBy(() -> service.create("user:read")).isInstanceOf(BadRequestException.class);
        verify(roles, never()).save(any());
    }

    @Test
    void aReservedAuthorityRoleNameIsRejected() {
        assertThatThrownBy(() -> service.create("MFA_COMPLETE")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.create("FACTOR_TOTP")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void creatingWithAnExistingNameThrowsConflict() {
        when(roles.findByName("ROLE_EDITOR")).thenReturn(Optional.of(new Role("ROLE_EDITOR")));

        assertThatThrownBy(() -> service.create("ROLE_EDITOR", Set.of()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void creatingWithAnUnknownPermissionThrowsBadRequest() {
        when(roles.findByName("ROLE_EDITOR")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create("ROLE_EDITOR", Set.of("not:a-permission")))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void getOrCreateReturnsTheExistingRoleWithoutSaving() {
        Role existing = new Role("ROLE_EDITOR");
        when(roles.findByName("ROLE_EDITOR")).thenReturn(Optional.of(existing));

        RoleRef result = service.getOrCreate("ROLE_EDITOR");

        assertThat(result).isSameAs(existing);
        verify(roles, never()).save(any());
    }

    @Test
    void addingAMemberGrantsTheRoleToThatUser() {
        UUID roleId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Role role = new Role("ROLE_EDITOR");
        AppUser user = mock(AppUser.class);
        when(roles.findById(roleId)).thenReturn(Optional.of(role));
        when(users.findById(userId)).thenReturn(Optional.of(user));

        service.addMember(roleId, userId);

        verify(user).addRole(role);
    }

    @Test
    void removingAMemberRevokesTheRoleFromThatUser() {
        UUID roleId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Role role = new Role("ROLE_EDITOR");
        AppUser user = mock(AppUser.class);
        when(roles.findById(roleId)).thenReturn(Optional.of(role));
        when(users.findById(userId)).thenReturn(Optional.of(user));

        service.removeMember(roleId, userId);

        verify(user).removeRole(role);
    }

    @Test
    void addingAMemberToAMissingRoleThrowsNotFound() {
        UUID roleId = UUID.randomUUID();
        when(roles.findById(roleId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addMember(roleId, UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
        verify(users, never()).findById(any());
    }

    @Test
    void addingAMissingUserThrowsNotFound() {
        UUID roleId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(roles.findById(roleId)).thenReturn(Optional.of(new Role("ROLE_EDITOR")));
        when(users.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addMember(roleId, userId))
                .isInstanceOf(NotFoundException.class);
    }
}
