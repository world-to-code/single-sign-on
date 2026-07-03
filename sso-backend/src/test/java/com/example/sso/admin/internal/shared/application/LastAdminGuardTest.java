package com.example.sso.admin.internal.shared.application;

import com.example.sso.shared.error.ConflictException;
import com.example.sso.user.RoleRef;
import com.example.sso.user.RoleService;
import com.example.sso.user.Roles;
import com.example.sso.user.UserAccount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LastAdminGuard}: the actor-independent invariant that at least one enabled
 * administrator must remain. Rejects removing the last one (409) and is a no-op in every other case.
 */
@ExtendWith(MockitoExtension.class)
class LastAdminGuardTest {

    @Mock private RoleService roleService;
    @InjectMocks private LastAdminGuard guard;

    @Test
    void remainsEnabledAdminSkipsTheCheckEntirely() {
        guard.ensureNotLastAdmin(UUID.randomUUID(), true);

        verify(roleService, never()).findByName(Roles.ADMIN);
    }

    @Test
    void aNoOpWhenTheAdminRoleDoesNotExist() {
        when(roleService.findByName(Roles.ADMIN)).thenReturn(Optional.empty());

        assertThatCode(() -> guard.ensureNotLastAdmin(UUID.randomUUID(), false)).doesNotThrowAnyException();
    }

    @Test
    void rejectsRemovingTheOnlyEnabledAdmin() {
        UUID target = UUID.randomUUID();
        stubAdminMembers(List.of(enabled(target)));

        assertThatThrownBy(() -> guard.ensureNotLastAdmin(target, false)).isInstanceOf(ConflictException.class);
    }

    @Test
    void allowsWhenAnotherEnabledAdminRemains() {
        UUID target = UUID.randomUUID();
        stubAdminMembers(List.of(enabled(target), enabled(UUID.randomUUID())));

        assertThatCode(() -> guard.ensureNotLastAdmin(target, false)).doesNotThrowAnyException();
    }

    @Test
    void allowsWhenTheTargetIsNotAnEnabledAdmin() {
        stubAdminMembers(List.of(enabled(UUID.randomUUID())));

        assertThatCode(() -> guard.ensureNotLastAdmin(UUID.randomUUID(), false)).doesNotThrowAnyException();
    }

    @Test
    void rejectsWhenTheOnlyOtherAdminIsDisabled() {
        UUID target = UUID.randomUUID();
        UserAccount targetAdmin = enabled(target);
        UserAccount disabledPeer = member(UUID.randomUUID(), false);
        stubAdminMembers(List.of(targetAdmin, disabledPeer));

        // A disabled peer is not "another enabled admin", so the target is still the last one.
        assertThatThrownBy(() -> guard.ensureNotLastAdmin(target, false)).isInstanceOf(ConflictException.class);
    }

    private void stubAdminMembers(List<UserAccount> members) {
        UUID roleId = UUID.randomUUID();
        RoleRef adminRole = mock(RoleRef.class);
        when(adminRole.getId()).thenReturn(roleId);
        when(roleService.findByName(Roles.ADMIN)).thenReturn(Optional.of(adminRole));
        when(roleService.members(roleId)).thenReturn(members);
    }

    private UserAccount enabled(UUID id) {
        return member(id, true);
    }

    private UserAccount member(UUID id, boolean enabled) {
        UserAccount user = mock(UserAccount.class);
        // lenient: which of these the guard reads depends on list order / short-circuiting per test.
        lenient().when(user.getId()).thenReturn(id);
        lenient().when(user.isEnabled()).thenReturn(enabled);
        return user;
    }
}
