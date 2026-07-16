package com.example.sso.user.internal.application;

import com.example.sso.user.internal.account.domain.AppUser;
import com.example.sso.user.internal.group.domain.UserGroupRepository;
import com.example.sso.user.internal.role.domain.Role;
import com.example.sso.user.internal.role.domain.RoleRepository;
import com.example.sso.user.rbac.Permissions;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Assembles a user's full effective authority set — role names (ROLE_*) for global/system roles plus every
 * permission name, with the role-hierarchy's inherited permissions and the mutating⇒read implications folded
 * in. This is the SINGLE source of the assembly so the two callers can never drift: the login principal
 * ({@link SsoUserDetailsService}) and the id-addressable re-validation of a mapping rule's author
 * ({@code UserService.effectiveAuthorities}). Reads are RLS-scoped to the caller's context, so an author is
 * re-checked in the rule's own tier — the same scope in which they were authorized.
 */
@Component
@RequiredArgsConstructor
class EffectiveAuthorityResolver {

    private final UserGroupRepository groups;
    private final RoleRepository roles;
    private final RbacHydrator hydrator;
    private final RoleInheritanceResolver inheritanceResolver;

    /** The effective authority strings for {@code user} (hydrated in place). Must run inside a transaction. */
    Set<String> authoritiesOf(AppUser user) {
        // RBAC: role names (ROLE_*) from roles assigned directly AND delegated via the user's groups.
        // PBAC: permissions carried by those roles AND granted directly, PLUS the permissions of every role
        // those roles INHERIT down the DAG (permission names ONLY, never an inherited role's name). Finally
        // each mutating resource:action implies resource:read (Permissions.expandImplied). Every read is explicit.
        hydrator.hydrateUser(user);
        List<Role> groupRoles = groupDelegatedRoles(user.getId());

        Set<UUID> heldRoleIds = Stream.concat(user.getRoles().stream(), groupRoles.stream())
                .map(Role::getId).collect(Collectors.toSet());

        Stream<String> directRoleAuthorities = roleAuthorities(user.getRoles());
        Stream<String> groupRoleAuthorities = roleAuthorities(groupRoles);
        Stream<String> directPermissions = user.getDirectPermissionNames().stream();
        Stream<String> inheritedPermissions = inheritanceResolver.effectivePermissionNames(heldRoleIds).stream();

        Set<String> granted = Stream.of(directRoleAuthorities, groupRoleAuthorities,
                        directPermissions, inheritedPermissions)
                .flatMap(s -> s)
                .collect(Collectors.toSet());
        return Permissions.expandImplied(granted);
    }

    /** Roles delegated to the user via any (RLS-visible) group they belong to, with permission names hydrated. */
    private List<Role> groupDelegatedRoles(UUID userId) {
        List<UUID> roleIds = groups.findDelegatedRoleIdsForMember(userId);
        return roleIds.isEmpty() ? List.of() : hydrator.hydrateRoles(roles.findAllById(new HashSet<>(roleIds)));
    }

    /**
     * A role's authorities: its permission names always, plus its name (ROLE_*) for a GLOBAL role or an org's
     * provisioned SYSTEM role (the well-known name authorization and console-entry assignment key on). A
     * tenant's CUSTOM role contributes only its permissions, so an org role named e.g. {@code ROLE_ADMIN}
     * cannot escalate. Org-scoped roles resolve here only when this runs in that org's context.
     */
    private Stream<String> roleAuthorities(Collection<Role> roles) {
        return roles.stream().flatMap(role -> Stream.concat(
                role.getOrgId() == null || role.isSystem() ? Stream.of(role.getName()) : Stream.empty(),
                role.getPermissionNames().stream()));
    }
}
