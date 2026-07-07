package com.example.sso.user.internal.application;

import com.example.sso.user.LoginResolutionScope;
import com.example.sso.user.Permissions;
import com.example.sso.user.internal.domain.UserGroupRepository;
import com.example.sso.user.internal.domain.AppUser;
import com.example.sso.user.internal.domain.AppUserRepository;
import com.example.sso.user.internal.domain.Role;
import com.example.sso.user.internal.domain.RoleRepository;
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
    private final LoginResolutionScope loginScope;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        AppUser user = resolve(username)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user: " + username));

        // RBAC: role names (ROLE_*) from roles assigned directly AND delegated via the user's groups.
        // PBAC: permissions carried by those roles AND granted directly to the user. Finally, each
        // resource:action permission implies resource:read (see Permissions.expandImplied). Every read is
        // an EXPLICIT join query — hydrator loads the roles (with permission names) from the join tables.
        hydrator.hydrateUser(user); // direct roles (+ their permission names) and direct permission names
        List<Role> groupRoles = groupDelegatedRoles(user.getId());

        Stream<String> directRoleAuthorities = roleAuthorities(user.getRoles());
        Stream<String> groupRoleAuthorities = roleAuthorities(groupRoles);
        Stream<String> directPermissions = user.getDirectPermissionNames().stream();

        Set<String> granted = Stream.of(directRoleAuthorities, groupRoleAuthorities, directPermissions)
                .flatMap(s -> s)
                .collect(Collectors.toSet());
        List<SimpleGrantedAuthority> authorities = Permissions.expandImplied(granted).stream()
                .map(SimpleGrantedAuthority::new)
                .toList();

        return principal(user, authorities);
    }

    /**
     * Resolves the login within the customer (고객사) the orchestrator bound for this authentication: the
     * password provider passes only a username, so once usernames are per-customer the resolver must scope
     * to the login's customer (falling back to a global account) rather than pick a same-named user from
     * another tenant. With no scope bound (a non-login caller), falls back to the plain global lookup.
     */
    private Optional<AppUser> resolve(String username) {
        return loginScope.current()
                .map(scope -> users.findByUsernameInCustomer(username, scope.customerId()))
                .orElseGet(() -> users.findByUsername(username));
    }

    /** Roles delegated to the user via any (RLS-visible) group they belong to, with permission names hydrated. */
    private List<Role> groupDelegatedRoles(UUID userId) {
        List<UUID> roleIds = groups.findDelegatedRoleIdsForMember(userId);
        return roleIds.isEmpty() ? List.of() : hydrator.hydrateRoles(roles.findAllById(new HashSet<>(roleIds)));
    }

    /**
     * A role's authorities: its permission names always, plus its name (ROLE_*) ONLY for a global/system
     * role. A tenant (org) role contributes only its permissions, so an org role name never becomes a
     * granted authority — an org role named e.g. {@code ROLE_ADMIN} can't escalate, and org role names stay
     * unrestricted. (Org-scoped roles resolve here only when this runs in that org's context — see the
     * completion service, which loads authorities bound to the login org.)
     */
    private Stream<String> roleAuthorities(Collection<Role> roles) {
        return roles.stream().flatMap(role -> Stream.concat(
                role.getOrgId() == null ? Stream.of(role.getName()) : Stream.empty(),
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
