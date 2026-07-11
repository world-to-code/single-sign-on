package com.example.sso.user.internal.application;

import com.example.sso.user.role.Roles;

import com.example.sso.user.account.LoginResolutionScope;
import com.example.sso.user.rbac.Permissions;
import com.example.sso.user.internal.group.domain.UserGroupRepository;
import com.example.sso.user.internal.account.domain.AppUser;
import com.example.sso.user.internal.account.domain.AppUserRepository;
import com.example.sso.user.internal.role.domain.Role;
import com.example.sso.user.internal.role.domain.RoleRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads users for form login (and as the principal source for OIDC/SAML ceremonies).
 *
 * <p>Returns Spring Security's {@link User} principal so that the OAuth2 Authorization
 * Server's JDBC store can serialize it with its security-allowlisted Jackson mapper.
 * Domain data (id, email, display name) is resolved from {@link AppUser} by username where
 * needed, keeping the persisted principal minimal.
 */
@Service
@RequiredArgsConstructor
public class SsoUserDetailsService implements UserDetailsService {

    private final AppUserRepository users;
    private final UserGroupRepository groups;
    private final RoleRepository roles;
    private final RbacHydrator hydrator;
    private final RoleInheritanceResolver inheritanceResolver;
    private final LoginResolutionScope loginScope;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        AppUser user = resolve(username)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user: " + username));

        // RBAC: role names (ROLE_*) from roles assigned directly AND delegated via the user's groups.
        // PBAC: permissions carried by those roles AND granted directly to the user, PLUS the permissions
        // of every role those roles INHERIT down the role-hierarchy DAG (inheritanceResolver — permission
        // names ONLY, never an inherited role's name). Finally, each mutating resource:action implies
        // resource:read (see Permissions.expandImplied). Every read is an EXPLICIT join query.
        hydrator.hydrateUser(user); // direct roles (+ their permission names) and direct permission names
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
        List<SimpleGrantedAuthority> authorities = Permissions.expandImplied(granted).stream()
                .map(SimpleGrantedAuthority::new)
                .toList();

        return principal(user, authorities);
    }

    /**
     * Resolves the login within the organization (the tenant) the orchestrator bound for this authentication:
     * the password provider passes only a username, so once usernames are per-organization the resolver must
     * scope to the login's org (falling back to a global account) rather than pick a same-named user from
     * another tenant. With no scope bound (a non-login caller), falls back to the plain global lookup.
     */
    private Optional<AppUser> resolve(String username) {
        // With no scope bound (a non-login caller — a login always binds one), resolve only a GLOBAL account:
        // the plain findByUsername now queries a per-org, non-unique column, so a bare lookup could match
        // several rows. Fail closed (empty → UsernameNotFoundException) for an org-scoped user rather than 500.
        return loginScope.current()
                .map(scope -> users.findByUsernameInOrg(username, scope.orgId()))
                .orElseGet(() -> users.findByUsernameInOrg(username, null));
    }

    /** Roles delegated to the user via any (RLS-visible) group they belong to, with permission names hydrated. */
    private List<Role> groupDelegatedRoles(UUID userId) {
        List<UUID> roleIds = groups.findDelegatedRoleIdsForMember(userId);
        return roleIds.isEmpty() ? List.of() : hydrator.hydrateRoles(roles.findAllById(new HashSet<>(roleIds)));
    }

    /**
     * A role's authorities: its permission names always, plus its name (ROLE_*) for a GLOBAL role or an
     * org's provisioned SYSTEM role (the org's own ROLE_USER/ROLE_GROUP_ADMIN/ROLE_ORG_ADMIN — the
     * well-known name is what authorization checks and the console-entry assignment key on). A tenant's
     * CUSTOM role contributes only its permissions: a tenant can neither create nor rename a system role,
     * so an org role named e.g. {@code ROLE_ADMIN} can't escalate, and custom org role names stay
     * unrestricted. (Org-scoped roles resolve here only when this runs in that org's context — see the
     * completion service, which loads authorities bound to the login org.)
     */
    private Stream<String> roleAuthorities(Collection<Role> roles) {
        return roles.stream().flatMap(role -> Stream.concat(
                role.getOrgId() == null || role.isSystem() ? Stream.of(role.getName()) : Stream.empty(),
                role.getPermissionNames().stream()));
    }

    private UserDetails principal(AppUser user, List<SimpleGrantedAuthority> authorities) {
        boolean locked = !user.isAccountNonLocked() || user.isTemporarilyLocked(Instant.now());

        return User.withUsername(user.getUsername())
                .password(user.getPasswordHash() == null ? "" : user.getPasswordHash())
                .disabled(!user.isEnabled())
                .accountLocked(locked)
                .authorities(authorities)
                .build();
    }
}
