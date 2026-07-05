package com.example.sso.user.internal.application;

import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.user.GroupDeletedEvent;
import com.example.sso.user.GroupSpec;
import com.example.sso.user.internal.domain.UserGroupRepository;
import com.example.sso.user.internal.domain.AppUserRepository;
import com.example.sso.user.internal.domain.RoleRepository;
import com.example.sso.user.internal.domain.UserGroup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UserGroupServiceImpl}: system-group protection across every mutator, the
 * duplicate-name guard, and the delete side effect. The delete publishing a {@link GroupDeletedEvent}
 * (and the system-group path publishing NOTHING) are the unit's contract, so both use {@code verify}.
 */
@ExtendWith(MockitoExtension.class)
class UserGroupServiceImplTest {

    @Mock private UserGroupRepository repository;
    @Mock private AppUserRepository users;
    @Mock private RoleRepository roles;
    @Mock private ApplicationEventPublisher events;
    @Mock private AccessChangePublisher accessChanges;

    @InjectMocks private UserGroupServiceImpl service;

    private UserGroup systemGroup() {
        UserGroup group = new UserGroup(UserGroup.ALL_USERS, null, null);
        group.markSystem();
        return group;
    }

    @Test
    void createRejectsADuplicateName() {
        GroupSpec spec = new GroupSpec("Engineering", "d", null, Set.of());
        when(repository.findByName("Engineering")).thenReturn(Optional.of(new UserGroup("Engineering", "d", null)));

        assertThatThrownBy(() -> service.create(spec)).isInstanceOf(ConflictException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void createPersistsANewGroup() {
        GroupSpec spec = new GroupSpec("Engineering", "d", null, Set.of());
        // The saved entity must carry an id so toView() can project it (getId().toString()).
        UserGroup saved = spy(new UserGroup("Engineering", "d", null));
        doReturn(UUID.randomUUID()).when(saved).getId();
        when(repository.findByName("Engineering")).thenReturn(Optional.empty());
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
        group.setMembers(Set.of(memberId));
        when(repository.findById(id)).thenReturn(Optional.of(group));

        service.delete(id);

        verify(repository).delete(group);
        verify(events).publishEvent(new GroupDeletedEvent(id));
        verify(accessChanges).forUserIds(Set.of(memberId)); // members lose the group's delegated roles
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

}
