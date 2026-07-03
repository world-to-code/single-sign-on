package com.example.sso.admin.internal.shared.application;

import com.example.sso.admin.internal.audit.application.AuditScope;
import com.example.sso.resource.ApplicationAuthorization;
import com.example.sso.resource.GroupAuthorization;
import com.example.sso.resource.ResourceAuthorization;
import com.example.sso.resource.UserAuthorization;
import com.example.sso.user.RoleRef;
import com.example.sso.user.RoleService;
import com.example.sso.user.Roles;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the instance-level (ABAC) rules in {@link AdminAccessPolicy}. Adversarial focus: scope
 * (super-bypass OR self OR resource subtree), privileged-role assignment being super-only, the
 * self-protection guards (no self-disable/-delete/-demotion), and fail-closed behaviour when the acting
 * user cannot be resolved from the security context.
 */
class AdminAccessPolicyTest {

    private static final String ACTOR_NAME = "alice";
    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final UUID OTHER_ID = UUID.randomUUID();

    private UserService userService;
    private RoleService roleService;
    private UserAuthorization userAuth;
    private GroupAuthorization groupAuth;
    private ApplicationAuthorization appAuth;
    private ResourceAuthorization resourceAuth;
    private AdminAccessPolicy policy;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        roleService = mock(RoleService.class);
        userAuth = mock(UserAuthorization.class);
        groupAuth = mock(GroupAuthorization.class);
        appAuth = mock(ApplicationAuthorization.class);
        resourceAuth = mock(ResourceAuthorization.class);
        policy = new AdminAccessPolicy(userService, roleService, userAuth, groupAuth, appAuth, resourceAuth);

        UserAccount actor = mock(UserAccount.class);
        when(actor.getId()).thenReturn(ACTOR_ID);
        when(userService.findByUsername(ACTOR_NAME)).thenReturn(Optional.of(actor));
        signIn();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // --- canAccessUser: the scope union ---

    @Test
    void superAdminCanAccessAnyUser() {
        when(resourceAuth.isUnscoped(ACTOR_ID)).thenReturn(true);
        assertThat(policy.canAccessUser(OTHER_ID)).isTrue();
    }

    @Test
    void anyoneCanAccessThemselves() {
        assertThat(policy.canAccessUser(ACTOR_ID)).isTrue();
    }

    @Test
    void resourceSubtreeCanManageGrantsUserAccess() {
        when(userAuth.canManage(ACTOR_ID, OTHER_ID)).thenReturn(true);
        assertThat(policy.canAccessUser(OTHER_ID)).isTrue();
    }

    @Test
    void unrelatedUserIsOutOfScope() {
        assertThat(policy.canAccessUser(OTHER_ID)).isFalse();
    }

    @Test
    void unresolvedActorFailsClosedOnUserAccess() {
        when(userService.findByUsername(ACTOR_NAME)).thenReturn(Optional.empty());
        assertThat(policy.canAccessUser(OTHER_ID)).isFalse();
    }

    // --- group / app scope delegation ---

    @Test
    void canAccessGroupDelegatesToGroupAuthorization() {
        UUID groupId = UUID.randomUUID();
        when(groupAuth.canManage(ACTOR_ID, groupId)).thenReturn(true);
        assertThat(policy.canAccessGroup(groupId)).isTrue();
        assertThat(policy.canAccessGroup(UUID.randomUUID())).isFalse();
    }

    @Test
    void canAccessAppDelegatesToApplicationAuthorization() {
        when(appAuth.canManage(ACTOR_ID, "app-1")).thenReturn(true);
        assertThat(policy.canAccessApp("app-1")).isTrue();
        assertThat(policy.canAccessApp("app-2")).isFalse();
    }

    @Test
    void isCurrentActorUnscopedDelegatesToResourceAuthorization() {
        when(resourceAuth.isUnscoped(ACTOR_ID)).thenReturn(true);
        assertThat(policy.isCurrentActorUnscoped()).isTrue();
    }

    @Test
    void currentManagedUserIdsComeFromTheResourceSubtree() {
        UUID resource = UUID.randomUUID();
        when(userAuth.scopedUserIds(ACTOR_ID)).thenReturn(Set.of(resource));

        assertThat(policy.currentManagedUserIds()).containsExactly(resource);
    }

    @Test
    void currentManagedUserIdsIsEmptyForUnresolvedActor() {
        when(userService.findByUsername(ACTOR_NAME)).thenReturn(Optional.empty());
        assertThat(policy.currentManagedUserIds()).isEmpty();
    }

    // --- privileged-role assignment: super-only ---

    @Test
    void nonSuperAdminMayNotAssignPrivilegedRoles() {
        assertThat(policy.mayAssignRoles(Set.of(Roles.ADMIN))).isFalse();
    }

    @Test
    void nonSuperAdminMayAssignOrdinaryRoles() {
        assertThat(policy.mayAssignRoles(Set.of("ROLE_SUPPORT"))).isTrue();
    }

    @Test
    void superAdminMayAssignPrivilegedRoles() {
        makeActorSuper();
        assertThat(policy.mayAssignRoles(Set.of(Roles.GROUP_ADMIN))).isTrue();
    }

    @Test
    void onlySuperAdminMayCreateUsers() {
        assertThat(policy.canCreateUser()).isFalse();
        makeActorSuper();
        assertThat(policy.canCreateUser()).isTrue();
    }

    // --- self-protection guards ---

    @Test
    void enablingIsAlwaysAllowedButSelfAndAdminDisableAreBlocked() {
        makeAdmin(OTHER_ID);
        assertThat(policy.canSetEnabled(ACTOR_ID, true)).isTrue();   // enabling self
        assertThat(policy.canSetEnabled(ACTOR_ID, false)).isFalse(); // disabling self
        assertThat(policy.canSetEnabled(OTHER_ID, false)).isFalse(); // disabling another admin
    }

    @Test
    void disablingAnOrdinaryUserIsAllowed() {
        assertThat(policy.canSetEnabled(OTHER_ID, false)).isTrue();
    }

    @Test
    void deletingSelfOrAnAdminIsBlocked() {
        makeAdmin(OTHER_ID);
        assertThat(policy.canDeleteUser(ACTOR_ID)).isFalse();
        assertThat(policy.canDeleteUser(OTHER_ID)).isFalse();
    }

    @Test
    void deletingAnOrdinaryUserIsAllowed() {
        assertThat(policy.canDeleteUser(OTHER_ID)).isTrue();
    }

    @Test
    void resettingOwnMfaIsAllowedButAnotherAdminsIsNot() {
        makeAdmin(ACTOR_ID);
        makeAdmin(OTHER_ID);
        assertThat(policy.canResetMfa(ACTOR_ID)).isTrue();
        assertThat(policy.canResetMfa(OTHER_ID)).isFalse();
    }

    @Test
    void managingPermissionsIsSuperOnlyAndNeverAnotherAdmins() {
        makeAdmin(OTHER_ID);
        assertThat(policy.canManagePermissions(OTHER_ID)).isFalse(); // not super
        makeActorSuper();
        assertThat(policy.canManagePermissions(OTHER_ID)).isFalse(); // super, but target is an admin
        assertThat(policy.canManagePermissions(ACTOR_ID)).isTrue();  // super, self
    }

    // --- canUpdateUser ---

    @Test
    void scopedAdminMayNotTouchAnAdministratorAccount() {
        makeAdmin(OTHER_ID);
        assertThat(policy.canUpdateUser(OTHER_ID, true, Set.of())).isFalse();
    }

    @Test
    void scopedAdminMayNotAssignAPrivilegedRole() {
        assertThat(policy.canUpdateUser(OTHER_ID, true, Set.of(Roles.ADMIN))).isFalse();
    }

    @Test
    void anAdministratorCannotBeDisabledViaUpdate() {
        makeActorSuper();
        makeAdmin(OTHER_ID);
        assertThat(policy.canUpdateUser(OTHER_ID, false, Set.of(Roles.ADMIN))).isFalse();
    }

    @Test
    void superAdminMayDemoteAnotherAdmin() {
        makeActorSuper();
        makeAdmin(OTHER_ID);
        assertThat(policy.canUpdateUser(OTHER_ID, true, Set.of())).isTrue();
    }

    @Test
    void cannotDisableOwnAccountViaUpdate() {
        makeActorSuper();
        assertThat(policy.canUpdateUser(ACTOR_ID, false, Set.of(Roles.ADMIN))).isFalse();
    }

    @Test
    void cannotSelfRevokeOwnAdminRole() {
        makeActorSuper();
        assertThat(policy.canUpdateUser(ACTOR_ID, true, Set.of())).isFalse();
    }

    @Test
    void mayUpdateSelfWhileKeepingOwnAdminRole() {
        makeActorSuper();
        assertThat(policy.canUpdateUser(ACTOR_ID, true, Set.of(Roles.ADMIN))).isTrue();
    }

    // --- currentAuditScope ---

    @Test
    void auditScopeForASuperAdminIsUnscoped() {
        when(resourceAuth.isUnscoped(ACTOR_ID)).thenReturn(true);

        AuditScope scope = policy.currentAuditScope();

        assertThat(scope.unscoped()).isTrue();
        assertThat(scope.actorUsername()).isEqualTo(ACTOR_NAME);
    }

    @Test
    void auditScopeForADelegateUnionsTheirManagedIds() {
        UUID user = UUID.randomUUID();
        UUID group = UUID.randomUUID();
        UUID resource = UUID.randomUUID();
        when(resourceAuth.isUnscoped(ACTOR_ID)).thenReturn(false);
        when(userAuth.scopedUserIds(ACTOR_ID)).thenReturn(Set.of(user));
        when(groupAuth.scopedGroupIds(ACTOR_ID)).thenReturn(Set.of(group));
        when(appAuth.scopedAppIds(ACTOR_ID)).thenReturn(Set.of("app-9"));
        when(resourceAuth.managedResourceIds(ACTOR_ID)).thenReturn(Set.of(resource));

        AuditScope scope = policy.currentAuditScope();

        assertThat(scope.unscoped()).isFalse();
        assertThat(scope.actorUsername()).isEqualTo(ACTOR_NAME);
        assertThat(scope.userIds()).containsExactly(user);
        assertThat(scope.groupIds()).containsExactly(group);
        assertThat(scope.appIds()).containsExactly("app-9");
        assertThat(scope.resourceIds()).containsExactly(resource);
    }

    @Test
    void auditScopeForAnUnresolvedActorIsEmptyAndNotUnscoped() {
        when(userService.findByUsername(ACTOR_NAME)).thenReturn(Optional.empty());

        AuditScope scope = policy.currentAuditScope();

        assertThat(scope.unscoped()).isFalse();
        assertThat(scope.userIds()).isEmpty();
        assertThat(scope.groupIds()).isEmpty();
        assertThat(scope.appIds()).isEmpty();
        assertThat(scope.resourceIds()).isEmpty();
    }

    // --- role-membership grant/revoke (from a role's member list) ---

    @Test
    void scopedAdminMayGrantAnOrdinaryRoleToAManagedUser() {
        UUID roleId = stubRole("ROLE_SUPPORT");
        when(userAuth.canManage(ACTOR_ID, OTHER_ID)).thenReturn(true);

        assertThat(policy.canGrantRole(OTHER_ID, roleId)).isTrue();
    }

    @Test
    void scopedAdminMayNotGrantAPrivilegedRole() {
        UUID roleId = stubRole(Roles.ADMIN);
        when(userAuth.canManage(ACTOR_ID, OTHER_ID)).thenReturn(true);

        assertThat(policy.canGrantRole(OTHER_ID, roleId)).isFalse();
    }

    @Test
    void scopedAdminMayNotGrantAnyRoleToAnAdministrator() {
        UUID roleId = stubRole("ROLE_SUPPORT");
        makeAdmin(OTHER_ID);
        when(userAuth.canManage(ACTOR_ID, OTHER_ID)).thenReturn(true);

        assertThat(policy.canGrantRole(OTHER_ID, roleId)).isFalse();
    }

    @Test
    void scopedAdminMayNotGrantAnOrdinaryRoleToAnOutOfScopeUser() {
        UUID roleId = stubRole("ROLE_SUPPORT");

        assertThat(policy.canGrantRole(OTHER_ID, roleId)).isFalse();
    }

    @Test
    void superAdminMayGrantAPrivilegedRoleToAnyUser() {
        makeActorSuper();
        when(resourceAuth.isUnscoped(ACTOR_ID)).thenReturn(true);
        UUID roleId = stubRole(Roles.ADMIN);

        assertThat(policy.canGrantRole(OTHER_ID, roleId)).isTrue();
    }

    @Test
    void adminMayNotRevokeTheirOwnAdminRole() {
        makeActorSuper();
        when(resourceAuth.isUnscoped(ACTOR_ID)).thenReturn(true);
        UUID roleId = stubRole(Roles.ADMIN);

        assertThat(policy.canRevokeRole(ACTOR_ID, roleId)).isFalse();
    }

    @Test
    void superAdminMayRevokeAnotherAdministratorsAdminRole() {
        makeActorSuper();
        when(resourceAuth.isUnscoped(ACTOR_ID)).thenReturn(true);
        makeAdmin(OTHER_ID);
        UUID roleId = stubRole(Roles.ADMIN);

        assertThat(policy.canRevokeRole(OTHER_ID, roleId)).isTrue();
    }

    /** Registers a role of the given name in the roleService mock and returns its id. */
    private UUID stubRole(String name) {
        UUID roleId = UUID.randomUUID();
        RoleRef role = mock(RoleRef.class);
        when(role.getName()).thenReturn(name);
        when(roleService.findById(roleId)).thenReturn(Optional.of(role));
        return roleId;
    }

    private void makeActorSuper() {
        when(userService.hasRole(ACTOR_ID, Roles.ADMIN)).thenReturn(true);
    }

    private void makeAdmin(UUID userId) {
        when(userService.hasRole(userId, Roles.ADMIN)).thenReturn(true);
    }

    private void signIn() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                ACTOR_NAME, null, List.of(new SimpleGrantedAuthority(Roles.ADMIN))));
    }
}
