package com.example.sso.admin.internal.shared.application;

import com.example.sso.admin.internal.audit.application.AuditScope;
import com.example.sso.resource.authorization.ApplicationAuthorization;
import com.example.sso.resource.authorization.GroupAuthorization;
import com.example.sso.resource.authorization.ResourceAuthorization;
import com.example.sso.organization.OrganizationAuthorization;
import com.example.sso.portal.application.ApplicationService;
import com.example.sso.portal.application.ApplicationView;
import com.example.sso.resource.authorization.UserAuthorization;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.Permissions;
import com.example.sso.user.RoleHierarchyService;
import com.example.sso.user.RoleRef;
import com.example.sso.user.RoleService;
import com.example.sso.user.Roles;
import com.example.sso.user.UserGroupService;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
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
    private RoleHierarchyService roleHierarchy;
    private UserGroupService userGroups;
    private UserAuthorization userAuth;
    private GroupAuthorization groupAuth;
    private ApplicationAuthorization appAuth;
    private ResourceAuthorization resourceAuth;
    private OrganizationAuthorization orgAuth;
    private ApplicationService applications;
    private OrgContext orgContext;
    private AdminAccessPolicy policy;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        roleService = mock(RoleService.class);
        roleHierarchy = mock(RoleHierarchyService.class);
        userGroups = mock(UserGroupService.class);
        userAuth = mock(UserAuthorization.class);
        groupAuth = mock(GroupAuthorization.class);
        appAuth = mock(ApplicationAuthorization.class);
        resourceAuth = mock(ResourceAuthorization.class);
        orgAuth = mock(OrganizationAuthorization.class);
        applications = mock(ApplicationService.class);
        orgContext = mock(OrgContext.class);
        policy = new AdminAccessPolicy(userService, roleService, roleHierarchy, userGroups, userAuth, groupAuth,
                appAuth, resourceAuth, orgAuth, applications, orgContext);

        UserAccount actor = mock(UserAccount.class);
        when(actor.getId()).thenReturn(ACTOR_ID);
        // The actor resolves globally when they hold ROLE_ADMIN (the default super-admin sign-in), else within
        // their own org — stub both paths leniently so each test's sign-in picks the right one.
        lenient().when(userService.findByUsername(ACTOR_NAME)).thenReturn(Optional.of(actor));
        lenient().when(userService.findByUsernameInOrg(ACTOR_NAME, null)).thenReturn(Optional.of(actor));
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
    void aTenantAdminReachesAUserInTheirBoundOrg() {
        // No resource-subtree delegation, but the actor administers the bound org AND the target belongs to
        // it → in scope (a tenant admin manages their whole org's directory).
        UUID orgId = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(orgId));
        when(orgAuth.canManage(ACTOR_ID, orgId)).thenReturn(true);
        when(userService.orgIdOf(OTHER_ID)).thenReturn(Optional.of(orgId));

        assertThat(policy.canAccessUser(OTHER_ID)).isTrue();
    }

    @Test
    void aTenantAdminCannotReachAUserInAnotherOrg() {
        // The actor administers the bound org, but the target belongs to a DIFFERENT org → out of scope,
        // even though app_user has no RLS to hide the row.
        UUID orgId = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(orgId));
        when(orgAuth.canManage(ACTOR_ID, orgId)).thenReturn(true);
        when(userService.orgIdOf(OTHER_ID)).thenReturn(Optional.of(UUID.randomUUID()));

        assertThat(policy.canAccessUser(OTHER_ID)).isFalse();
    }

    @Test
    void aTenantAdminMayCreateUsersWithinTheirOrg() {
        UUID orgId = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(orgId));
        when(orgAuth.canManage(ACTOR_ID, orgId)).thenReturn(true); // administers the bound org

        assertThat(policy.canCreateUser()).isTrue();
    }

    @Test
    void unresolvedActorFailsClosedOnUserAccess() {
        when(userService.findByUsernameInOrg(ACTOR_NAME, null)).thenReturn(Optional.empty());
        assertThat(policy.canAccessUser(OTHER_ID)).isFalse();
    }

    @Test
    void aSuperAdminActorResolvesGloballyNotByADrilledOrgUsernameCollision() {
        // Per-org uniqueness lets an attacker plant the super-admin's username in the org they drilled into.
        // The acting super-admin (ROLE_ADMIN) MUST resolve to their GLOBAL account, never the org-scoped
        // impostor — otherwise the guards would judge the wrong identity (a confused deputy).
        UserAccount impostor = mock(UserAccount.class);
        lenient().when(impostor.getId()).thenReturn(UUID.randomUUID());
        lenient().when(userService.findByUsername(ACTOR_NAME)).thenReturn(Optional.of(impostor)); // drilled-org row
        when(resourceAuth.isUnscoped(ACTOR_ID)).thenReturn(true); // the REAL (global) super-admin is unscoped

        // Resolves ACTOR_ID (global super-admin) via findByUsernameInOrg(name, null), so the super bypass applies.
        assertThat(policy.canAccessUser(OTHER_ID)).isTrue();
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
    void canAccessGroupAllowsATenantAdminOfTheGroupsOwningOrg() {
        // No resource-subtree delegation, but the actor administers the org that owns the group → allowed.
        UUID groupId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        when(groupAuth.canManage(ACTOR_ID, groupId)).thenReturn(false);
        when(userGroups.orgIdOf(groupId)).thenReturn(Optional.of(orgId));
        when(orgAuth.canManage(ACTOR_ID, orgId)).thenReturn(true);

        assertThat(policy.canAccessGroup(groupId)).isTrue();
    }

    @Test
    void canAccessGroupDeniesAGlobalGroupForANonSuperActor() {
        // A global group has no administering org (orgIdOf empty), so a tenant admin cannot mutate it even
        // though RLS lets them read it — keeps a platform-wide group out of a tenant's reach.
        UUID groupId = UUID.randomUUID();
        when(groupAuth.canManage(ACTOR_ID, groupId)).thenReturn(false);
        when(userGroups.orgIdOf(groupId)).thenReturn(Optional.empty());

        assertThat(policy.canAccessGroup(groupId)).isFalse();
    }

    @Test
    void administersBoundOrgOnlyWhenActingWithinAnOrgTheActorManages() {
        UUID orgId = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(orgId));
        when(orgAuth.canManage(ACTOR_ID, orgId)).thenReturn(true);
        assertThat(policy.administersBoundOrg()).isTrue();

        when(orgAuth.canManage(ACTOR_ID, orgId)).thenReturn(false); // bound, but not an admin of it (e.g. delegate)
        assertThat(policy.administersBoundOrg()).isFalse();

        when(orgContext.currentOrg()).thenReturn(Optional.empty()); // unbound (platform) context
        assertThat(policy.administersBoundOrg()).isFalse();
    }

    @Test
    void canAccessAppDelegatesToApplicationAuthorization() {
        when(appAuth.canManage(ACTOR_ID, "app-1")).thenReturn(true);
        assertThat(policy.canAccessApp("app-1")).isTrue();
        assertThat(policy.canAccessApp("app-2")).isFalse();
    }

    @Test
    void aTenantAdminCanManageAnAppInTheirOwnOrgsCatalogButNotOthers() {
        // No resource-subtree delegation, but the actor administers the bound org and the app is in that org's
        // tier-scoped catalog → manageable. An app NOT in their catalog (another tenant's, or a global one) is
        // refused — so a tenant admin can assign/manage the apps they just registered, and only those.
        UUID orgId = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(orgId));
        when(orgAuth.canManage(ACTOR_ID, orgId)).thenReturn(true);
        when(applications.listApplications()).thenReturn(List.of(
                new ApplicationView("my-app", "OIDC", "My App", "/launch", false, null, null)));

        assertThat(policy.canAccessApp("my-app")).isTrue();
        assertThat(policy.canAccessApp("another-tenants-app")).isFalse();
    }

    @Test
    void canAccessOrgDelegatesToOrganizationAuthorizationAndSuperBypasses() {
        UUID orgId = UUID.randomUUID();
        when(orgAuth.canManage(ACTOR_ID, orgId)).thenReturn(true);
        assertThat(policy.canAccessOrg(orgId)).isTrue();          // scoped org-admin of that org
        assertThat(policy.canAccessOrg(UUID.randomUUID())).isFalse(); // not their org

        when(resourceAuth.isUnscoped(ACTOR_ID)).thenReturn(true);
        assertThat(policy.canAccessOrg(UUID.randomUUID())).isTrue();  // super admin bypasses
    }

    @Test
    void currentScopedOrgIdsComeFromOrganizationAuthorization() {
        UUID org = UUID.randomUUID();
        when(orgAuth.scopedOrgIds(ACTOR_ID)).thenReturn(Set.of(org));

        assertThat(policy.currentScopedOrgIds()).containsExactly(org);
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
        when(userService.findByUsernameInOrg(ACTOR_NAME, null)).thenReturn(Optional.empty());
        assertThat(policy.currentManagedUserIds()).isEmpty();
    }

    // --- privileged-role assignment: super-only ---

    @Test
    void nonSuperAdminMayNotAssignARoleAboveThemEvenHoldingItsPermissions() {
        // ROLE_ADMIN is strictly above the actor — actorMayManageRoleName is false (mock default) — so it is
        // unassignable regardless of any other stub. "Not above" is a REQUIRED conjunct, not merely holdings.
        assertThat(policy.mayAssignRoles(Set.of(Roles.ADMIN))).isFalse();
    }

    @Test
    void nonSuperAdminMayAssignAPeerRoleAtTheirOwnLevel() {
        // A tenant ORG_ADMIN manages their whole tier: a role at their OWN level (not above them) that carries
        // no platform perm and whose permissions they hold IS assignable — they can hand out ROLE_ORG_ADMIN to
        // another tenant user. Escalation is still blocked by grant-only-what-you-hold + the "not above" rule.
        UUID roleId = UUID.randomUUID();
        RoleRef peer = mock(RoleRef.class);
        when(peer.getId()).thenReturn(roleId);
        when(roleService.findByName("ROLE_PEER", null)).thenReturn(Optional.of(peer));
        when(roleService.permissionNames(roleId)).thenReturn(Set.of(Permissions.USER_READ));
        signInWith(Permissions.USER_READ);
        when(roleHierarchy.actorMayManageRoleName(ACTOR_ID, "ROLE_PEER", null)).thenReturn(true);

        assertThat(policy.mayAssignRoles(Set.of("ROLE_PEER"))).isTrue();
    }

    @Test
    void nonSuperAdminMayNotAssignARoleStrictlyAboveThemEvenHoldingAllItsPermissions() {
        // A role ABOVE the actor is unassignable even when the actor holds all of its permissions and it carries
        // no platform perm — "not above" is the guard that stops self-escalation to a higher role.
        UUID roleId = UUID.randomUUID();
        RoleRef higher = mock(RoleRef.class);
        when(higher.getId()).thenReturn(roleId);
        when(roleService.findByName("ROLE_HIGHER", null)).thenReturn(Optional.of(higher));
        when(roleService.permissionNames(roleId)).thenReturn(Set.of(Permissions.USER_READ));
        signInWith(Permissions.USER_READ); // holds the perm...
        when(roleHierarchy.actorMayManageRoleName(ACTOR_ID, "ROLE_HIGHER", null)).thenReturn(false); // ...but ABOVE

        assertThat(policy.mayAssignRoles(Set.of("ROLE_HIGHER"))).isFalse();
    }

    @Test
    void nonSuperAdminMayNotAssignARoleCarryingAPlatformPermission() {
        // A super may have built a role (even one below the actor) that bundles a platform perm; a scoped admin
        // must not assign it and inherit that permission (indirection escalation). Dominance is stubbed TRUE so
        // the platform-perm conjunct is the sole reason for refusal.
        UUID roleId = UUID.randomUUID();
        RoleRef reporting = mock(RoleRef.class);
        when(reporting.getId()).thenReturn(roleId);
        when(roleService.findByName("ROLE_REPORTING", null)).thenReturn(Optional.of(reporting));
        when(roleService.permissionNames(roleId)).thenReturn(Set.of(Permissions.ORG_CREATE));
        when(roleHierarchy.actorMayManageRoleName(ACTOR_ID, "ROLE_REPORTING", null)).thenReturn(true);

        assertThat(policy.mayAssignRoles(Set.of("ROLE_REPORTING"))).isFalse();
    }

    @Test
    void nonSuperAdminMayNotGrantARoleCarryingAPermissionTheyLackToAUser() {
        UUID roleId = UUID.randomUUID();
        RoleRef reporting = mock(RoleRef.class);
        lenient().when(reporting.getName()).thenReturn("ROLE_REPORTING");
        when(roleService.findById(roleId)).thenReturn(Optional.of(reporting)); // roleName() lookup
        when(roleService.permissionNames(roleId)).thenReturn(Set.of(Permissions.KEY_ROTATE));
        when(roleHierarchy.actorMayManageRole(ACTOR_ID, roleId)).thenReturn(true); // dominance OK; holdings is the blocker

        assertThat(policy.canGrantRole(OTHER_ID, roleId)).isFalse();
    }

    @Test
    void nonSuperAdminMayAssignAnOrdinaryRoleTheyMayManage() {
        UUID roleId = UUID.randomUUID();
        RoleRef support = mock(RoleRef.class);
        when(support.getId()).thenReturn(roleId);
        when(roleService.findByName("ROLE_SUPPORT", null)).thenReturn(Optional.of(support));
        when(roleService.permissionNames(roleId)).thenReturn(Set.of());
        when(roleHierarchy.actorMayManageRoleName(ACTOR_ID, "ROLE_SUPPORT", null)).thenReturn(true);

        assertThat(policy.mayAssignRoles(Set.of("ROLE_SUPPORT"))).isTrue();
    }

    // --- direct permissions: a tenant admin manages their own org, bounded by what they hold ---

    @Test
    void aTenantAdminMayManageTheirOwnOrgsUsersDirectPermissions() {
        // A tenant admin owns their tenant: they may set a user's direct permissions. The permissions
        // themselves are bounded by mayGrantPermissions, not by blocking the operation outright.
        UUID orgId = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(orgId));
        when(orgAuth.canManage(ACTOR_ID, orgId)).thenReturn(true);

        assertThat(policy.canManagePermissions(OTHER_ID)).isTrue();
    }

    @Test
    void aTenantAdminMayNotManageAnAdministratorsDirectPermissions() {
        UUID orgId = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(orgId));
        lenient().when(orgAuth.canManage(ACTOR_ID, orgId)).thenReturn(true);
        makeAdmin(OTHER_ID);

        assertThat(policy.canManagePermissions(OTHER_ID)).isFalse();
    }

    @Test
    void aScopedAdminWhoAdministersNoOrgMayNotManageDirectPermissions() {
        // A resource delegate (no org administration) stays blocked — otherwise they could self-grant.
        assertThat(policy.canManagePermissions(OTHER_ID)).isFalse();
    }

    @Test
    void aNonSuperMayGrantOnlyPermissionsTheyThemselvesHold() {
        signInWith(Permissions.USER_READ, Permissions.USER_UPDATE);

        assertThat(policy.mayGrantPermissions(Set.of(Permissions.USER_READ))).isTrue();
        // scim:manage is tenant-grantable, but the actor does not hold it — handing it out escalates.
        assertThat(policy.mayGrantPermissions(Set.of(Permissions.SCIM_MANAGE))).isFalse();
    }

    @Test
    void aNonSuperMayNotGrantANonCatalogAuthorityAsAPermission() {
        // currentAuthorities() mixes permissions with role names and session markers, so a bare
        // "the actor holds this string" test would let MFA_COMPLETE / ROLE_ORG_ADMIN through as a
        // "permission" and mint a real authority on the target. The gate must check the CATALOG itself.
        signInWith("MFA_COMPLETE", Roles.ORG_ADMIN, "FACTOR_TOTP", Permissions.USER_READ);

        assertThat(policy.mayGrantPermissions(Set.of("MFA_COMPLETE"))).isFalse();
        assertThat(policy.mayGrantPermissions(Set.of(Roles.ORG_ADMIN))).isFalse();
        assertThat(policy.mayGrantPermissions(Set.of("FACTOR_TOTP"))).isFalse();
        assertThat(policy.mayGrantPermissions(Set.of(Permissions.USER_READ))).isTrue();
    }

    @Test
    void aNonSuperMayNeverGrantAPlatformPermission() {
        signInWith(Permissions.ORG_CREATE); // even if somehow held, it is platform-only
        assertThat(policy.mayGrantPermissions(Set.of(Permissions.ORG_CREATE))).isFalse();
    }

    @Test
    void aSuperAdminMayGrantAnyPermission() {
        makeActorSuper();
        assertThat(policy.mayGrantPermissions(Set.of(Permissions.ORG_CREATE))).isTrue();
    }

    @Test
    void nonSuperAdminMayNotAssignARoleTheCheckCannotResolve() {
        // Fail closed: a name that resolves to no role in the acting tier must not pass the gate. Passing it
        // (the old orElse(true)) let the SERVICE resolve an org role the CHECK never inspected.
        assertThat(policy.mayAssignRoles(Set.of("ROLE_UNKNOWN"))).isFalse();
    }

    @Test
    void nonSuperAdminMayNotAssignAnOrgRoleCarryingAPermissionTheyDoNotHold() {
        // The check must resolve the role in the ACTING TENANT's tier — the same tier the assignment resolves
        // in. An org-only role name that the global lookup misses would otherwise sail through the gate and
        // then be assigned, handing the target a permission the actor lacks (privilege escalation).
        UUID orgId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(orgId));
        RoleRef appManager = mock(RoleRef.class);
        when(appManager.getId()).thenReturn(roleId);
        when(roleService.findByName("appManager", orgId)).thenReturn(Optional.of(appManager));
        when(roleService.permissionNames(roleId)).thenReturn(Set.of(Permissions.CLIENT_CREATE));
        when(roleHierarchy.actorMayManageRoleName(ACTOR_ID, "appManager", orgId)).thenReturn(true);

        // The actor holds only user:create/user:read (see signInWith) — never oidc-client:create.
        signInWith(Permissions.USER_CREATE, Permissions.USER_READ);
        assertThat(policy.mayAssignRoles(Set.of("appManager"))).isFalse();
    }

    @Test
    void nonSuperAdminMayNotAssignARoleCarryingAPermissionTheyDoNotHold() {
        // Grant-only-what-you-hold: the actor does not hold user:read, so they may not assign a role that
        // carries it (which would let them, or a group they join, inherit an authority they lack).
        UUID roleId = UUID.randomUUID();
        RoleRef reader = mock(RoleRef.class);
        when(reader.getId()).thenReturn(roleId);
        when(roleService.findByName("ROLE_READER", null)).thenReturn(Optional.of(reader));
        when(roleService.permissionNames(roleId)).thenReturn(Set.of(Permissions.USER_READ));
        when(roleHierarchy.actorMayManageRoleName(ACTOR_ID, "ROLE_READER", null)).thenReturn(true);

        assertThat(policy.mayAssignRoles(Set.of("ROLE_READER"))).isFalse();
    }

    @Test
    void nonSuperAdminMayAssignAManageableRoleWhosePermissionsTheyAllHold() {
        signInWith(Permissions.USER_READ); // holds user:read, but is not a super admin (hasRole not stubbed)
        UUID roleId = UUID.randomUUID();
        RoleRef reader = mock(RoleRef.class);
        when(reader.getId()).thenReturn(roleId);
        when(roleService.findByName("ROLE_READER", null)).thenReturn(Optional.of(reader));
        when(roleService.permissionNames(roleId)).thenReturn(Set.of(Permissions.USER_READ));
        when(roleHierarchy.actorMayManageRoleName(ACTOR_ID, "ROLE_READER", null)).thenReturn(true);

        assertThat(policy.mayAssignRoles(Set.of("ROLE_READER"))).isTrue();
    }

    @Test
    void scopedAdminMayNotGrantARoleCarryingAPermissionTheyDoNotHold() {
        UUID roleId = stubRole("ROLE_READER");
        when(roleService.permissionNames(roleId)).thenReturn(Set.of(Permissions.USER_READ));
        when(userAuth.canManage(ACTOR_ID, OTHER_ID)).thenReturn(true);
        when(roleHierarchy.actorMayManageRole(ACTOR_ID, roleId)).thenReturn(true); // dominance OK; holdings blocks

        assertThat(policy.canGrantRole(OTHER_ID, roleId)).isFalse();
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
    void scopedAdminMayNotUpdateAUserAssigningAGlobalRoleCarryingAPlatformPermission() {
        // The create→update escalation: a tenant admin creates a puppet, then UPDATEs it to add a super-created
        // global role that bundles a PLATFORM permission (e.g. organization:create). canUpdateUser must refuse it, exactly
        // as canCreateUser's mayAssignRoles gate does — otherwise the puppet gains cross-tenant reach.
        UUID roleId = UUID.randomUUID();
        RoleRef auditor = mock(RoleRef.class);
        when(auditor.getId()).thenReturn(roleId);
        when(roleService.findByName("ROLE_AUDITOR", null)).thenReturn(Optional.of(auditor));
        when(roleService.permissionNames(roleId)).thenReturn(Set.of(Permissions.ORG_CREATE));
        when(roleHierarchy.actorMayManageRoleName(ACTOR_ID, "ROLE_AUDITOR", null)).thenReturn(true);

        assertThat(policy.canUpdateUser(OTHER_ID, true, Set.of("ROLE_AUDITOR"))).isFalse();
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
        when(userService.findByUsernameInOrg(ACTOR_NAME, null)).thenReturn(Optional.empty());

        AuditScope scope = policy.currentAuditScope();

        assertThat(scope.unscoped()).isFalse();
        assertThat(scope.userIds()).isEmpty();
        assertThat(scope.groupIds()).isEmpty();
        assertThat(scope.appIds()).isEmpty();
        assertThat(scope.resourceIds()).isEmpty();
    }

    // --- role-membership grant/revoke (from a role's member list) ---

    @Test
    void scopedAdminMayGrantARoleTheyMayManageToAManagedUser() {
        UUID roleId = stubRole("ROLE_SUPPORT");
        when(userAuth.canManage(ACTOR_ID, OTHER_ID)).thenReturn(true);
        when(roleHierarchy.actorMayManageRole(ACTOR_ID, roleId)).thenReturn(true);

        assertThat(policy.canGrantRole(OTHER_ID, roleId)).isTrue();
    }

    @Test
    void scopedAdminMayNotGrantARoleAboveThem() {
        // A role strictly ABOVE the actor (actorMayManageRole false) is ungrantable even to a managed user and
        // even with its permissions held — the "not above" conjunct fails.
        UUID roleId = stubRole("ROLE_ORG_ADMIN");
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
    void scopedAdminMayNotGrantAManageableRoleToAnOutOfScopeUser() {
        UUID roleId = stubRole("ROLE_SUPPORT");
        when(roleHierarchy.actorMayManageRole(ACTOR_ID, roleId)).thenReturn(true); // dominance OK; scope blocks

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
        signInWith(Roles.ADMIN);
    }

    /** Signs the actor in holding exactly the given authorities (role names and/or permission strings). */
    private void signInWith(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                ACTOR_NAME, null, Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
    }
}
