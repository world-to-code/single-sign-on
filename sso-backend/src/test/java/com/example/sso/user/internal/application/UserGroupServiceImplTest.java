package com.example.sso.user.internal.application;

import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.group.GroupDeletedEvent;
import com.example.sso.user.group.GroupSpec;
import com.example.sso.user.internal.account.domain.AppUser;
import com.example.sso.user.internal.account.domain.AppUserRepository;
import com.example.sso.user.internal.role.domain.RoleRepository;
import com.example.sso.user.internal.group.domain.UserGroup;
import com.example.sso.user.internal.group.domain.UserGroupMemberRepository;
import com.example.sso.user.internal.group.domain.UserGroupRepository;
import com.example.sso.user.internal.group.domain.UserGroupRoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UserGroupServiceImpl}: system-group protection across every mutator, the
 * duplicate-name guard, and the delete side effect. The delete publishing a {@link GroupDeletedEvent}
 * (and the system-group path publishing NOTHING) are the unit's contract, so both use {@code verify}.
 * Membership/role rows are now managed through the explicit join repositories.
 */
@ExtendWith(MockitoExtension.class)
class UserGroupServiceImplTest {

    @Mock private UserGroupRepository repository;
    @Mock private UserGroupMemberRepository members;
    @Mock private UserGroupRoleRepository groupRoles;
    @Mock private AppUserRepository users;
    @Mock private RoleRepository roles;
    @Mock private ApplicationEventPublisher events;
    @Mock private AccessChangePublisher accessChanges;
    @Mock private OrgContext orgContext;
    @Mock private RbacHydrator hydrator;

    @InjectMocks private UserGroupServiceImpl service;

    @BeforeEach
    void defaults() {
        // Default to the global tier (no bound org) for group creation; tests may override.
        lenient().when(orgContext.currentOrg()).thenReturn(Optional.empty());
        // Empty join-table views by default so toView() can project without extra stubbing.
        lenient().when(members.findUserIdsByGroupId(any())).thenReturn(List.of());
        lenient().when(groupRoles.findRoleIdsByGroupId(any())).thenReturn(List.of());
        lenient().when(roles.findAllById(any())).thenReturn(List.of());
    }

    private UserGroup systemGroup() {
        UserGroup group = new UserGroup(UserGroup.ALL_USERS, null, null);
        group.markSystem();
        return group;
    }

    @Test
    void createRejectsADuplicateName() {
        GroupSpec spec = new GroupSpec("Engineering", "d", null, Set.of());
        when(repository.findByNameAndOrgIdIsNull("Engineering"))
                .thenReturn(Optional.of(new UserGroup("Engineering", "d", null)));

        assertThatThrownBy(() -> service.create(spec)).isInstanceOf(ConflictException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void createPersistsANewGroup() {
        GroupSpec spec = new GroupSpec("Engineering", "d", null, Set.of());
        // The saved entity must carry an id so toView() can project it (getId().toString()).
        UserGroup saved = spy(new UserGroup("Engineering", "d", null));
        doReturn(UUID.randomUUID()).when(saved).getId();
        when(repository.findByNameAndOrgIdIsNull("Engineering")).thenReturn(Optional.empty());
        when(repository.save(any(UserGroup.class))).thenReturn(saved);

        service.create(spec);

        verify(repository).save(any(UserGroup.class));
    }

    @Test
    void deleteOfASystemGroupThrowsConflictAndPublishesNothing() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(systemGroup()));

        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(ConflictException.class);
        verify(repository, never()).delete(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void deleteOfANormalGroupRemovesItAndEndsMemberSessions() {
        UUID id = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UserGroup group = new UserGroup("Engineering", "d", null);
        when(repository.findById(id)).thenReturn(Optional.of(group));
        when(members.findUserIdsByGroupId(id)).thenReturn(List.of(memberId));

        service.delete(id);

        verify(members).deleteByGroupId(id); // explicit join cleanup before the group row
        verify(groupRoles).deleteByGroupId(id);
        verify(repository).delete(group);
        verify(events).publishEvent(new GroupDeletedEvent(id));
        verify(accessChanges).membershipChanged(Set.of(memberId)); // ends sessions + announces the membership
    }

    @Test
    void deleteOfAMissingGroupThrowsNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void setRolesOnASystemGroupThrowsConflict() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(systemGroup()));

        assertThatThrownBy(() -> service.setRoles(id, Set.of("ROLE_USER")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void setRolesRejectsAnUnknownRole() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(new UserGroup("Engineering", "d", null)));
        when(roles.findByNameAndOrgIdIsNull("ROLE_GHOST")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setRoles(id, Set.of("ROLE_GHOST")))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void updateOfASystemGroupThrowsConflict() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(systemGroup()));

        assertThatThrownBy(() -> service.update(id, new GroupSpec("x", "d", null, Set.of())))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void setMembersRejectsAUserFromADifferentOrg() {
        UUID id = UUID.randomUUID();
        UUID orgA = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(new UserGroup("Engineering", "d", null, orgA)));
        AppUser other = new AppUser("bob", "bob@x.io", "Bob", "hash", UUID.randomUUID()); // a different org
        when(users.findAllById(Set.of(userId))).thenReturn(List.of(other));

        assertThatThrownBy(() -> service.setMembers(id, Set.of(userId)))
                .isInstanceOf(BadRequestException.class);
        verify(members, never()).save(any());
    }

    @Test
    void setMembersRejectsAGlobalUserForATenantGroup() {
        UUID id = UUID.randomUUID();
        UUID orgA = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(new UserGroup("Engineering", "d", null, orgA)));
        AppUser global = new AppUser("root", "root@x.io", "Root", "hash", null); // a global (super-admin) user
        when(users.findAllById(Set.of(userId))).thenReturn(List.of(global));

        assertThatThrownBy(() -> service.setMembers(id, Set.of(userId)))
                .isInstanceOf(BadRequestException.class);
        verify(members, never()).save(any());
    }

    @Test
    void setMembersAcceptsASameOrgUser() {
        UUID id = UUID.randomUUID();
        UUID orgA = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UserGroup group = spy(new UserGroup("Engineering", "d", null, orgA));
        doReturn(id).when(group).getId();
        when(repository.findById(id)).thenReturn(Optional.of(group));
        AppUser member = spy(new AppUser("alice", "alice@x.io", "Alice", "hash", orgA)); // same org
        doReturn(userId).when(member).getId();
        when(users.findAllById(Set.of(userId))).thenReturn(List.of(member));

        service.setMembers(id, Set.of(userId));

        verify(members).save(any());
    }
}
