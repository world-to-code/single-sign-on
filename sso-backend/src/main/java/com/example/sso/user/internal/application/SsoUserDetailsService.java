package com.example.sso.user.internal.application;

import com.example.sso.user.internal.domain.AppUser;
import com.example.sso.user.internal.domain.AppUserRepository;
import com.example.sso.user.internal.domain.Permission;
import java.time.Instant;
import java.util.List;
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

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        AppUser user = users.findWithAuthoritiesByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user: " + username));

        // RBAC: role names (ROLE_*). PBAC: permissions from roles AND directly granted to the user.
        Stream<String> roleAuthorities = user.getRoles().stream()
                .flatMap(role -> Stream.concat(
                        Stream.of(role.getName()),
                        role.getPermissions().stream().map(Permission::getName)));
        Stream<String> directPermissions = user.getDirectPermissions().stream().map(Permission::getName);
        List<SimpleGrantedAuthority> authorities = Stream.concat(roleAuthorities, directPermissions)
                .distinct()
                .map(SimpleGrantedAuthority::new)
                .toList();

        boolean locked = !user.isAccountNonLocked() || user.isTemporarilyLocked(Instant.now());
        return User.withUsername(user.getUsername())
                .password(user.getPasswordHash() == null ? "" : user.getPasswordHash())
                .disabled(!user.isEnabled())
                .accountLocked(locked)
                .authorities(authorities)
                .build();
    }
}
