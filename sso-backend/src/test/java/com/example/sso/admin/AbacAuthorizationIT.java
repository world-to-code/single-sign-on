package com.example.sso.admin;

import com.example.sso.admin.internal.group.api.AdminGroupController;
import com.example.sso.admin.internal.group.api.SetGroupRolesRequest;
import com.example.sso.admin.internal.shared.application.AdminAccessPolicy;
import com.example.sso.admin.internal.user.application.UserAdminService;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.user.group.GroupSpec;
import com.example.sso.user.group.GroupView;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.rbac.Permissions;
import com.example.sso.user.group.UserGroupService;
import com.example.sso.user.account.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.example.sso.admin.internal.metadata.api.AttributeRequest;
import com.example.sso.admin.internal.metadata.api.MetadataAdminController;
import com.example.sso.metadata.Attribute;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Instance-level (ABAC) authorization: the actor-relative self-protection rules ({@link AdminAccessPolicy},
 * used from {@code @PreAuthorize}) and the actor-independent last-administrator invariant
 * ({@link UserAdminService}). Each test cleans up the users it creates so the global admin count is not
 * polluted for sibling tests (the Testcontainer DB is shared without per-test rollback).
 */
class AbacAuthorizationIT extends AbstractIntegrationTest {

    @Autowired
    AdminAccessPolicy access;
    @Autowired
    UserAdminService userAdmin;
    @Autowired
    UserService userService;
    @Autowired
    UserGroupService userGroups;
    @Autowired
    AdminGroupController groupController;
    @Autowired
    MetadataAdminController metadataController;

    private final List<UUID> created = new ArrayList<>();

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        created.forEach(id -> {
            try {
                userService.delete(id);
            } catch (RuntimeException ignored) {
                // best-effort cleanup
            }
        });
        created.clear();
    }

    @Test
    void selfProtectionRules() {
        UUID actor = create("abacactor", Set.of("ROLE_ADMIN", "ROLE_USER"));
        UUID other = create("abacother", Set.of("ROLE_USER"));
        actAs("abacactor");

        assertThat(access.canDeleteUser(actor)).isFalse();                          // cannot delete self
        assertThat(access.canDeleteUser(other)).isTrue();
        assertThat(access.canSetEnabled(actor, false)).isFalse();                   // cannot disable self
        assertThat(access.canSetEnabled(actor, true)).isTrue();                     // may re-enable self
        assertThat(access.canSetEnabled(other, false)).isTrue();
        assertThat(access.canUpdateUser(actor, true, Set.of("ROLE_USER"))).isFalse();               // drops own admin
        assertThat(access.canUpdateUser(actor, true, Set.of("ROLE_ADMIN", "ROLE_USER"))).isTrue();  // keeps own admin
        assertThat(access.canUpdateUser(actor, false, Set.of("ROLE_ADMIN"))).isFalse();             // self-disable
        assertThat(access.canUpdateUser(other, false, Set.of())).isTrue();
    }

    @Test
    void otherAdministratorsAreProtected() {
        create("abacactor", Set.of("ROLE_ADMIN", "ROLE_USER"));
        UUID otherAdmin = create("abacpeer", Set.of("ROLE_ADMIN", "ROLE_USER"));
        UUID plain = create("abacplain", Set.of("ROLE_USER"));
        actAs("abacactor");

        // Destructive ops on another admin are blocked; on a normal user they are allowed.
        assertThat(access.canDeleteUser(otherAdmin)).isFalse();
        assertThat(access.canDeleteUser(plain)).isTrue();
        assertThat(access.canSetEnabled(otherAdmin, false)).isFalse();
        assertThat(access.canSetEnabled(plain, false)).isTrue();
        assertThat(access.canResetMfa(otherAdmin)).isFalse();
        assertThat(access.canResetMfa(plain)).isTrue();
        assertThat(access.canManagePermissions(otherAdmin)).isFalse();
        assertThat(access.canManagePermissions(plain)).isTrue();

        // Disabling another admin via update is blocked, but demoting them (role edit) is allowed.
        assertThat(access.canUpdateUser(otherAdmin, false, Set.of("ROLE_ADMIN"))).isFalse();
        assertThat(access.canUpdateUser(otherAdmin, true, Set.of("ROLE_USER"))).isTrue();
    }

    @Test
    void lastAdministratorIsProtected() {
        UUID seededAdmin = userService.findByUsername("admin").orElseThrow().getId();

        // The seeded admin is the only enabled direct ROLE_ADMIN: removing it is rejected (before any write).
        assertThatThrownBy(() -> userAdmin.deleteUser(seededAdmin)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> userAdmin.setEnabled(seededAdmin, false)).isInstanceOf(ConflictException.class);

        // With a second enabled admin present, removing that second admin is allowed.
        UUID second = create("abacadmin2", Set.of("ROLE_ADMIN", "ROLE_USER"));
        userAdmin.deleteUser(second);
        created.remove(second);
    }

    @Test
    void scopedNonSuperAdminIsConfinedAndCannotEscalate() {
        // Scope now comes only from resource_role (covered by ScopedAdminSurfaceIsolationIT); here a
        // scoped (non-super) admin with no resource grant reaches nobody but themselves, and — crucially —
        // cannot escalate. A super admin is unscoped.
        UUID scopedAdmin = create("scopedadmin", Set.of("ROLE_GROUP_ADMIN", "ROLE_USER"));
        UUID other = create("scopedstranger", Set.of("ROLE_USER"));

        actAs("scopedadmin");
        assertThat(access.currentIsSuperAdmin()).isFalse();
        assertThat(access.canCreateUser()).isFalse();                 // scoped admins can't mint users
        assertThat(access.canAccessUser(scopedAdmin)).isTrue();       // self
        assertThat(access.canAccessUser(other)).isFalse();            // no resource scope → out of reach
        assertThat(access.currentManagedUserIds()).doesNotContain(other);

        // A scoped admin cannot escalate: no permission granting, no privileged-role assignment.
        assertThat(access.canManagePermissions(scopedAdmin)).isFalse();   // can't self-grant permissions
        assertThat(access.canManagePermissions(other)).isFalse();         // scoped admins can't manage perms
        assertThat(access.canUpdateUser(scopedAdmin, true, Set.of("ROLE_ADMIN", "ROLE_USER"))).isFalse(); // self-promote
        assertThat(access.canUpdateUser(other, true, Set.of("ROLE_ADMIN"))).isFalse();   // grant admin to another
        assertThat(access.canUpdateUser(other, true, Set.of("ROLE_USER"))).isTrue();     // ordinary edit is fine
        assertThat(access.mayAssignRoles(Set.of("ROLE_USER"))).isTrue();                 // delegation of a plain role
        assertThat(access.mayAssignRoles(Set.of("ROLE_ADMIN"))).isFalse();               // can't delegate admin
        assertThat(access.mayAssignRoles(Set.of("ROLE_GROUP_ADMIN"))).isFalse();

        actAs("admin"); // the seeded super admin is unscoped
        assertThat(access.currentIsSuperAdmin()).isTrue();
        assertThat(access.canCreateUser()).isTrue();
        assertThat(access.canAccessUser(other)).isTrue();
        assertThat(access.canManagePermissions(other)).isTrue();
        assertThat(access.canUpdateUser(other, true, Set.of("ROLE_ADMIN"))).isTrue();    // super may grant admin
        assertThat(access.mayAssignRoles(Set.of("ROLE_ADMIN"))).isTrue();                // super may delegate admin
    }

    /**
     * Proves the composed security annotation actually binds the method's {@code #request} argument:
     * {@code @CanAssignGroupRoles} = {@code hasAuthority('group:update') and
     * @adminAccessPolicy.mayAssignRoles(#request.roleNames())}, evaluated via the method-security proxy
     * (calling the controller bean directly triggers it; the HTTP elevation filter is not involved).
     * If {@code #request} did not bind, {@code #request.roleNames()} would error and every call would be
     * denied — so the passing positive case is the discriminator.
     */
    @Test
    void assignGroupRolesAnnotationBindsRequestArgument() {
        UUID groupId = UUID.fromString(userGroups.create(new GroupSpec("AnnBindDept", null, null, Set.of())).id());

        // Positive: a super admin assigning a plain role succeeds — #request.roleNames() bound & evaluated.
        actAsWithAuthorities("admin", Permissions.GROUP_UPDATE);
        GroupView updated = groupController.setGroupRoles(groupId, new SetGroupRolesRequest(Set.of("ROLE_USER")));
        assertThat(updated.roleNames()).contains("ROLE_USER");

        // Negative: a scoped (non-super) admin assigning a privileged role is denied through the same binding.
        create("annscoped", Set.of("ROLE_GROUP_ADMIN", "ROLE_USER"));
        actAsWithAuthorities("annscoped", Permissions.GROUP_UPDATE);
        assertThatThrownBy(() ->
                groupController.setGroupRoles(groupId, new SetGroupRolesRequest(Set.of("ROLE_ADMIN"))))
                .isInstanceOf(AccessDeniedException.class);

        actAsWithAuthorities("admin", Permissions.GROUP_UPDATE);
        userGroups.delete(groupId);
    }

    @Test
    void userMetadataEndpointsApplyBothThePermissionAndTheInstanceScope() {
        UUID target = create("metatarget", Set.of("ROLE_USER"));

        // Positive: a super admin holding user:update can tag any user. This is the discriminator — the write
        // endpoint's @PreAuthorize must reference only #id; a composite that dereferenced a non-existent request
        // field would throw during evaluation and deny even this call.
        actAsWithAuthorities("admin", Permissions.USER_UPDATE);
        assertThat(metadataController.addUserAttribute(target, new AttributeRequest("dept", "eng")))
                .extracting(Attribute::key).contains("dept");
        assertThat(metadataController.removeUserAttribute(target, "dept")).isEmpty();

        // Negative: a scoped (non-super) admin with user:update but no scope over `target` is denied — the
        // canAccessUser(#id) conjunct fires, not just the permission.
        create("metascoped", Set.of("ROLE_GROUP_ADMIN", "ROLE_USER"));
        actAsWithAuthorities("metascoped", Permissions.USER_UPDATE);
        assertThatThrownBy(() -> metadataController.addUserAttribute(target, new AttributeRequest("dept", "eng")))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> metadataController.userAttributes(target)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void groupMetadataWriteRequiresGroupUpdateNotJustRead() {
        UUID groupId = UUID.fromString(userGroups.create(new GroupSpec("MetaGroup", null, null, Set.of())).id());

        // group:read alone cannot WRITE metadata — neither add nor a value-granular delete.
        actAsWithAuthorities("admin", Permissions.GROUP_READ);
        assertThatThrownBy(() -> metadataController.addGroupAttribute(groupId, new AttributeRequest("region", "eu")))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> metadataController.removeGroupAttributeValue(groupId, "region", "eu"))
                .isInstanceOf(AccessDeniedException.class);

        // With group:update (and super scope) it succeeds.
        actAsWithAuthorities("admin", Permissions.GROUP_UPDATE);
        assertThat(metadataController.addGroupAttribute(groupId, new AttributeRequest("region", "eu")))
                .extracting(Attribute::key).contains("region");
        assertThat(metadataController.removeGroupAttributeValue(groupId, "region", "eu")).isEmpty();

        actAsWithAuthorities("admin", Permissions.GROUP_UPDATE);
        userGroups.delete(groupId);
    }

    @Test
    void userMetadataValueGranularDeleteRoutesByValueParamAndIsScopeGated() {
        UUID target = create("metaval", Set.of("ROLE_USER"));

        // A super admin adds two values under one key, then the ?value= delete drops ONLY that value (the
        // params="value" mapping), leaving the other; the whole-key delete then drops the rest.
        actAsWithAuthorities("admin", Permissions.USER_UPDATE);
        metadataController.addUserAttribute(target, new AttributeRequest("team", "infra"));
        metadataController.addUserAttribute(target, new AttributeRequest("team", "sre"));
        assertThat(metadataController.removeUserAttributeValue(target, "team", "infra"))
                .extracting(Attribute::value).containsExactly("sre");
        assertThat(metadataController.removeUserAttribute(target, "team")).isEmpty();

        // A scoped admin without scope over target is denied the value-granular delete (canAccessUser(#id) fires).
        create("metavalscoped", Set.of("ROLE_GROUP_ADMIN", "ROLE_USER"));
        actAsWithAuthorities("metavalscoped", Permissions.USER_UPDATE);
        assertThatThrownBy(() -> metadataController.removeUserAttributeValue(target, "team", "sre"))
                .isInstanceOf(AccessDeniedException.class);
    }

    private UUID create(String username, Set<String> roles) {
        UUID id = userService.createUser(new NewUser(username, username + "@example.com", username,
                "S3cret!pw9", roles)).getId();
        created.add(id);
        return id;
    }

    private void actAs(String username) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, null, List.of()));
    }

    private void actAsWithAuthorities(String username, String... authorities) {
        List<SimpleGrantedAuthority> granted = Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, null, granted));
    }
}
