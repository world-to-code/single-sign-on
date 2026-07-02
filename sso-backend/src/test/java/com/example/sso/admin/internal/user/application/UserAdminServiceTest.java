package com.example.sso.admin.internal.user.application;

import com.example.sso.admin.internal.shared.application.AdminAccessPolicy;
import com.example.sso.admin.internal.shared.application.AdminAuditLogger;
import com.example.sso.audit.AuditType;
import com.example.sso.mfa.MfaService;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.user.NewUser;
import com.example.sso.user.RbacService;
import com.example.sso.user.RoleRef;
import com.example.sso.user.RoleService;
import com.example.sso.user.Roles;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserGroupService;
import com.example.sso.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UserAdminService}. Focus: list results are scoped by the access policy, the
 * actor-independent last-administrator invariant rejects with a 409, a domain {@code IllegalArgument}
 * is surfaced as a 409, and the mutating operations write the audit trail (verify interactions).
 */
class UserAdminServiceTest {

    private UserService userService;
    private RoleService roleService;
    private RbacService rbacService;
    private MfaService mfaService;
    private UserGroupService userGroups;
    private AdminAccessPolicy accessPolicy;
    private AdminAuditLogger auditLogger;
    private UserAdminService service;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        roleService = mock(RoleService.class);
        rbacService = mock(RbacService.class);
        mfaService = mock(MfaService.class);
        userGroups = mock(UserGroupService.class);
        accessPolicy = mock(AdminAccessPolicy.class);
        auditLogger = mock(AdminAuditLogger.class);
        service = new UserAdminService(userService, roleService, rbacService, mfaService,
                userGroups, accessPolicy, auditLogger);
    }

    @Test
    void unscopedActorSeesEveryUserWithoutConsultingTheManagedSet() {
        UserAccount first = user(UUID.randomUUID());
        UserAccount second = user(UUID.randomUUID());
        when(accessPolicy.isCurrentActorUnscoped()).thenReturn(true);
        when(userService.findAll()).thenReturn(List.of(first, second));

        assertThat(service.listUsers()).hasSize(2);
        verify(accessPolicy, never()).currentManagedUserIds();
    }

    @Test
    void scopedActorOnlySeesManagedUsers() {
        UUID managed = UUID.randomUUID();
        when(accessPolicy.isCurrentActorUnscoped()).thenReturn(false);
        when(accessPolicy.currentManagedUserIds()).thenReturn(Set.of(managed));
        UserAccount managedUser = user(managed);
        UserAccount otherUser = user(UUID.randomUUID());
        when(userService.findAll()).thenReturn(List.of(managedUser, otherUser));

        List<AdminUserView> result = service.listUsers();

        assertThat(result).extracting(AdminUserView::id).containsExactly(managed.toString());
        verify(accessPolicy).currentManagedUserIds();
    }

    @Test
    void removingTheLastEnabledAdminIsRejectedWith409() {
        UUID targetId = UUID.randomUUID();
        RoleRef adminRole = mock(RoleRef.class);
        UUID roleId = UUID.randomUUID();
        when(adminRole.getId()).thenReturn(roleId);
        when(roleService.findByName(Roles.ADMIN)).thenReturn(Optional.of(adminRole));
        UserAccount lastAdmin = enabledAdmin(targetId);
        when(roleService.members(roleId)).thenReturn(List.of(lastAdmin));

        assertThatThrownBy(() -> service.deleteUser(targetId)).isInstanceOf(ConflictException.class);
        verify(userService, never()).delete(any());
        verify(auditLogger, never()).log(any(), any());
    }

    @Test
    void createUserSurfacesDomainIllegalArgumentAsConflict() {
        NewUser newUser = new NewUser("bob", "bob@example.com", "Bob", "pw", Set.of());
        when(userService.createUser(newUser)).thenThrow(new IllegalArgumentException("username taken"));

        assertThatThrownBy(() -> service.createUser(newUser)).isInstanceOf(ConflictException.class);
    }

    @Test
    void createUserAuditsTheCreation() {
        NewUser newUser = new NewUser("bob", "bob@example.com", "Bob", "pw", Set.of(Roles.USER));
        UserAccount created = user(UUID.randomUUID());
        when(userService.createUser(newUser)).thenReturn(created);

        service.createUser(newUser);

        verify(auditLogger).log(eq(AuditType.USER_CREATED), any());
    }

    @Test
    void deleteUserDelegatesAndAuditsWhenNotTheLastAdmin() {
        UUID targetId = UUID.randomUUID();
        when(roleService.findByName(Roles.ADMIN)).thenReturn(Optional.empty());

        service.deleteUser(targetId);

        verify(userService).delete(targetId);
        verify(auditLogger).log(eq(AuditType.USER_DELETED), any());
    }

    private UserAccount user(UUID id) {
        UserAccount account = mock(UserAccount.class);
        when(account.getId()).thenReturn(id);
        when(account.getRoles()).thenReturn(Set.of());
        when(account.getDirectPermissionNames()).thenReturn(Set.of());
        return account;
    }

    private UserAccount enabledAdmin(UUID id) {
        UserAccount account = mock(UserAccount.class);
        when(account.getId()).thenReturn(id);
        when(account.isEnabled()).thenReturn(true);
        return account;
    }
}
