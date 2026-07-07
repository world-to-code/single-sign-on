package com.example.sso.admin.internal.user.application;

import com.example.sso.admin.internal.shared.application.AdminAccessPolicy;
import com.example.sso.admin.internal.shared.application.AdminAuditLogger;
import com.example.sso.admin.internal.shared.application.LastAdminGuard;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.audit.AuditSubjectType;
import com.example.sso.audit.AuditType;
import com.example.sso.mfa.MfaService;
import com.example.sso.shared.Page;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.user.NewUser;
import com.example.sso.user.Roles;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserGroupService;
import com.example.sso.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UserAdminService}. Focus: directory pages are scoped in the DB by the access
 * policy, the actor-independent last-administrator invariant (delegated to {@link LastAdminGuard})
 * rejects with a 409, a domain {@code IllegalArgument} is surfaced as a 409, and the mutating operations
 * write the audit trail (verify interactions).
 */
class UserAdminServiceTest {

    private UserService userService;
    private MfaService mfaService;
    private UserGroupService userGroups;
    private AdminAccessPolicy accessPolicy;
    private AdminAuditLogger auditLogger;
    private LastAdminGuard lastAdminGuard;
    private OrgContext orgContext;
    private UserAdminService service;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        mfaService = mock(MfaService.class);
        userGroups = mock(UserGroupService.class);
        accessPolicy = mock(AdminAccessPolicy.class);
        auditLogger = mock(AdminAuditLogger.class);
        lastAdminGuard = mock(LastAdminGuard.class);
        orgContext = mock(OrgContext.class);
        service = new UserAdminService(userService, mfaService, userGroups, accessPolicy, auditLogger,
                lastAdminGuard, orgContext);
    }

    @Test
    void unscopedActorPagesEveryUserWithoutConsultingTheManagedSet() {
        UserAccount first = user(UUID.randomUUID());
        UserAccount second = user(UUID.randomUUID());
        when(accessPolicy.isCurrentActorUnscoped()).thenReturn(true);
        when(userService.findAll(0, 20)).thenReturn(new Page<>(2, 0, 20, List.of(first, second)));

        assertThat(service.listUsers(0, 20).items()).hasSize(2);
        verify(accessPolicy, never()).currentManagedUserIds();
        verify(userService, never()).findByIds(any(), anyInt(), anyInt());
    }

    @Test
    void scopedActorPagesOnlyTheManagedIdsInTheDatabase() {
        UUID managed = UUID.randomUUID();
        when(accessPolicy.isCurrentActorUnscoped()).thenReturn(false);
        when(accessPolicy.currentManagedUserIds()).thenReturn(Set.of(managed));
        UserAccount managedUser = user(managed);
        when(userService.findByIds(Set.of(managed), 0, 20)).thenReturn(new Page<>(1, 0, 20, List.of(managedUser)));

        Page<AdminUserView> result = service.listUsers(0, 20);

        assertThat(result.items()).extracting(AdminUserView::id).containsExactly(managed.toString());
        verify(userService, never()).findAll(anyInt(), anyInt());
    }

    @Test
    void deletingTheLastAdminIsRejectedWith409() {
        UUID targetId = UUID.randomUUID();
        doThrow(new ConflictException("cannot remove the last administrator"))
                .when(lastAdminGuard).ensureNotLastAdmin(targetId, false);

        assertThatThrownBy(() -> service.deleteUser(targetId)).isInstanceOf(ConflictException.class);
        verify(userService, never()).delete(any());
        verify(auditLogger, never()).log(any(), any(), any(), any());
    }

    @Test
    void createUserSurfacesDomainIllegalArgumentAsConflict() {
        NewUser newUser = new NewUser("bob", "bob@example.com", "Bob", "pw", Set.of());
        when(userService.createUser(eq(newUser), any())).thenThrow(new IllegalArgumentException("username taken"));

        assertThatThrownBy(() -> service.createUser(newUser)).isInstanceOf(ConflictException.class);
    }

    @Test
    void createUserAuditsTheCreation() {
        NewUser newUser = new NewUser("bob", "bob@example.com", "Bob", "pw", Set.of(Roles.USER));
        UserAccount created = user(UUID.randomUUID());
        when(userService.createUser(eq(newUser), any())).thenReturn(created);

        service.createUser(newUser);

        verify(auditLogger).log(eq(AuditType.USER_CREATED), eq(AuditSubjectType.USER), any(), any());
    }

    @Test
    void deleteUserDelegatesAndAuditsWhenNotTheLastAdmin() {
        UUID targetId = UUID.randomUUID();

        service.deleteUser(targetId);

        verify(lastAdminGuard).ensureNotLastAdmin(targetId, false);
        verify(userService).delete(targetId);
        verify(auditLogger).log(eq(AuditType.USER_DELETED), eq(AuditSubjectType.USER), any(), any());
    }

    private UserAccount user(UUID id) {
        UserAccount account = mock(UserAccount.class);
        when(account.getId()).thenReturn(id);
        when(account.getRoles()).thenReturn(Set.of());
        when(account.getDirectPermissionNames()).thenReturn(Set.of());
        return account;
    }
}
