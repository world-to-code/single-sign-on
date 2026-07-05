package com.example.sso.user.internal.application;

import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.user.NewUser;
import com.example.sso.user.PermissionGrantPolicy;
import com.example.sso.user.Permissions;
import com.example.sso.user.UserAccessChangedEvent;
import com.example.sso.user.UserDeletedEvent;
import com.example.sso.user.UserUpdate;
import com.example.sso.user.internal.domain.UserGroupRepository;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.Instant;
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
 * Unit tests for {@link UserServiceImpl}: duplicate rejection on create, role/permission resolution
 * guards, and the delete side effects. The service's job here IS the collaborator interaction, so
 * persistence and the {@link UserDeletedEvent} publish are asserted with {@code verify(...)}.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock private AppUserRepository users;
    @Mock private RoleRepository roles;
    @Mock private PermissionRepository permissions;
    @Mock private UserGroupRepository groups;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private ApplicationEventPublisher events;
    @Mock private PermissionGrantPolicy grantPolicy;

    @InjectMocks private UserServiceImpl service;

    private NewUser newUser(Set<String> roleNames) {
        return new NewUser("alice", "alice@example.com", "Alice", "pw", roleNames);
    }

    @Test
    void createUserEncodesPasswordSavesAndJoinsDefaultGroup() {
        when(users.existsByUsername("alice")).thenReturn(false);
        when(users.existsByEmail("alice@example.com")).thenReturn(false);
        when(passwordEncoder.encode("pw")).thenReturn("hash");
        when(users.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

        service.createUser(newUser(Set.of()));

        verify(users).save(any(AppUser.class));
        verify(groups).findByNameAndOrgIdIsNull("All Users");
    }

    @Test
    void createUserWithDuplicateUsernameThrowsConflict() {
        when(users.existsByUsername("alice")).thenReturn(true);

        assertThatThrownBy(() -> service.createUser(newUser(Set.of())))
                .isInstanceOf(ConflictException.class);
        verify(users, never()).save(any());
    }

    @Test
    void createUserWithDuplicateEmailThrowsConflict() {
        when(users.existsByUsername("alice")).thenReturn(false);
        when(users.existsByEmail("alice@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.createUser(newUser(Set.of())))
                .isInstanceOf(ConflictException.class);
        verify(users, never()).save(any());
    }

    @Test
    void createUserWithUnknownRoleThrowsBadRequest() {
        when(users.existsByUsername("alice")).thenReturn(false);
        when(users.existsByEmail("alice@example.com")).thenReturn(false);
        when(roles.findByNameAndOrgIdIsNull("ROLE_GHOST")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createUser(newUser(Set.of("ROLE_GHOST"))))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void deleteMissingUserThrowsNotFoundAndPublishesNothing() {
        UUID id = UUID.randomUUID();
        when(users.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(NotFoundException.class);
        verify(users, never()).deleteById(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void deleteRemovesTheUserAndPublishesUserDeletedEvent() {
        UUID id = UUID.randomUUID();
        AppUser user = mock(AppUser.class);
        when(user.getUsername()).thenReturn("bob");
        when(users.findById(id)).thenReturn(Optional.of(user));

        service.delete(id);

        verify(users).deleteById(id);
        verify(events).publishEvent(new UserDeletedEvent(id));
        verify(events).publishEvent(new UserAccessChangedEvent("bob")); // terminate the deleted user's sessions
    }

    @Test
    void setDirectPermissionsRejectsAnUnknownPermission() {
        UUID id = UUID.randomUUID();
        when(users.findById(id)).thenReturn(Optional.of(new AppUser("alice", "a@x", "A", "h")));

        assertThatThrownBy(() -> service.setDirectPermissions(id, Set.of("not:a-permission")))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void setDirectPermissionsRejectsAPlatformPermissionTheActorMayNotGrant() {
        // The direct-permission path enforces the same tier split as the role builder (deny-by-default).
        UUID id = UUID.randomUUID();
        when(users.findById(id)).thenReturn(Optional.of(new AppUser("alice", "a@x", "A", "h")));
        when(grantPolicy.mayGrant(Permissions.SCIM_MANAGE)).thenReturn(false); // e.g. a tenant admin

        assertThatThrownBy(() -> service.setDirectPermissions(id, Set.of(Permissions.SCIM_MANAGE)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void updateUserRejectsAnUnknownRole() {
        UUID id = UUID.randomUUID();
        when(users.findById(id)).thenReturn(Optional.of(new AppUser("alice", "a@x", "A", "h")));
        when(roles.findByNameAndOrgIdIsNull("ROLE_GHOST")).thenReturn(Optional.empty());

        UserUpdate update = new UserUpdate("Alice", "a@x", true, Set.of("ROLE_GHOST"));

        assertThatThrownBy(() -> service.updateUser(id, update)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void hasRoleTrueWhenTheUserHoldsIt() {
        UUID id = UUID.randomUUID();
        AppUser user = new AppUser("alice", "a@x", "A", "h");
        user.addRole(new Role("ROLE_ADMIN"));
        when(users.findById(id)).thenReturn(Optional.of(user));

        assertThat(service.hasRole(id, "ROLE_ADMIN")).isTrue();
        assertThat(service.hasRole(id, "ROLE_USER")).isFalse();
    }

    @Test
    void hasRoleFalseForAMissingUser() {
        UUID id = UUID.randomUUID();
        when(users.findById(id)).thenReturn(Optional.empty());

        assertThat(service.hasRole(id, "ROLE_ADMIN")).isFalse();
    }

    @Test
    void recordFailedLoginDelegatesToTheDomainMethod() {
        AppUser user = new AppUser("alice", "a@x", "A", "h");
        when(users.findByUsername("alice")).thenReturn(Optional.of(user));

        service.recordFailedLogin("alice", 1, Duration.ofMinutes(15));

        assertThat(user.isTemporarilyLocked(Instant.now())).isTrue();
    }
}
