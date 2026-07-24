package com.example.sso.user.internal.application;

import com.example.sso.user.internal.account.domain.AppUser;
import com.example.sso.user.internal.group.domain.UserGroupRepository;
import com.example.sso.user.internal.role.domain.Role;
import com.example.sso.user.internal.role.domain.RoleRepository;
import com.example.sso.user.role.RoleHierarchyService;
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
 * effective permission, with the role-hierarchy's inherited permissions, wildcard expansion, mutating⇒read
 * implication AND negative permissions (deny) folded in. This is the SINGLE source of the assembly so the two
 * callers can never drift: the login principal ({@link SsoUserDetailsService}) and the id-addressable
 * re-validation of a mapping rule's author ({@code UserService.effectiveAuthorities}).
 *
 * <p>Grants are RLS-scoped to the caller's context (an author is re-checked in the rule's own tier); denies are
 * read AS PLATFORM ({@link PermissionDenyReader}) so a deny the caller's scope cannot see still applies. The
 * per-level subtraction itself is pure ({@link DenyResolver}).
 */
@Component
@RequiredArgsConstructor
class EffectiveAuthorityResolver {

    private final UserGroupRepository groups;
    private final RoleRepository roles;
    private final RbacHydrator hydrator;
    private final RoleInheritanceResolver inheritanceResolver;
    private final RoleHierarchyService roleHierarchy;
    private final PermissionDenyReader denyReader;
    private final DenyResolver denyResolver;

    /** The effective authority strings for {@code user} (hydrated in place). Must run inside a transaction. */
    Set<String> authoritiesOf(AppUser user) {
        hydrator.hydrateUser(user);
        List<Role> groupRoles = groupDelegatedRoles(user.getId());
        Set<UUID> heldRoleIds = Stream.concat(user.getRoles().stream(), groupRoles.stream())
                .map(Role::getId).collect(Collectors.toSet());

        // ALLOW, split by specificity level so a more specific grant can out-rank a less specific deny:
        // USER = direct permissions; ROLE/GROUP = the held roles' + delegated roles' + inherited permissions.
        // Role NAMES are carried separately — a deny never removes a role name (only permissions).
        Set<String> roleNames = roleNames(user.getRoles(), groupRoles);
        Set<String> userAllow = new HashSet<>(user.getDirectPermissionNames());
        Set<String> roleAllow = rolePermissions(user.getRoles(), groupRoles, heldRoleIds);

        // DENY, read across tiers so an RLS-invisible deny still applies. The ROLE-subject apex carve-out is
        // applied HERE: only denies on the user's APEX (top) roles are read, so a deny on a role dominated by a
        // higher role the user holds is ignored — a subordinate cannot cut a superior, a base holder is cut.
        // Fail CLOSED on a corrupt hierarchy: an EMPTY apex from a NON-empty held set can only mean a cycle (an
        // acyclic non-empty DAG always has a top); rather than read NO role denies (dropping every deny), fall
        // back to every held role so a deny still applies. A genuinely role-less user keeps an empty set.
        Set<UUID> apexRoleIds = roleHierarchy.apexOf(heldRoleIds); // reuse the held set — no re-read
        Set<UUID> roleDenySubjects = apexRoleIds.isEmpty() && !heldRoleIds.isEmpty() ? heldRoleIds : apexRoleIds;
        Set<UUID> groupIds = new HashSet<>(groups.findGroupIdsByMember(user.getId()));
        DenyRows denies = denyReader.read(user.getId(), roleDenySubjects, groupIds, user.getOrgId());

        return denyResolver.effectiveAuthorities(new DenyInputs(userAllow, roleAllow, roleNames, denies));
    }

    /** Roles delegated to the user via any (RLS-visible) group they belong to, with permission names hydrated. */
    private List<Role> groupDelegatedRoles(UUID userId) {
        List<UUID> roleIds = groups.findDelegatedRoleIdsForMember(userId);
        return roleIds.isEmpty() ? List.of() : hydrator.hydrateRoles(roles.findAllById(new HashSet<>(roleIds)));
    }

    /**
     * The role NAMES (ROLE_*) a user carries: a GLOBAL role or an org's provisioned SYSTEM role contributes its
     * name (the well-known name authorization and console-entry assignment key on); a tenant's CUSTOM role
     * contributes only its permissions, so an org role named e.g. {@code ROLE_ADMIN} cannot escalate.
     */
    private Set<String> roleNames(Collection<Role> direct, Collection<Role> delegated) {
        return Stream.concat(direct.stream(), delegated.stream())
                .filter(role -> role.getOrgId() == null || role.isSystem())
                .map(Role::getName)
                .collect(Collectors.toSet());
    }

    /** Every permission the held + delegated roles carry, plus everything they inherit down the DAG. */
    private Set<String> rolePermissions(Collection<Role> direct, Collection<Role> delegated, Set<UUID> heldRoleIds) {
        Set<String> permissions = new HashSet<>(inheritanceResolver.effectivePermissionNames(heldRoleIds));
        Stream.concat(direct.stream(), delegated.stream())
                .forEach(role -> permissions.addAll(role.getPermissionNames()));
        return permissions;
    }
}
