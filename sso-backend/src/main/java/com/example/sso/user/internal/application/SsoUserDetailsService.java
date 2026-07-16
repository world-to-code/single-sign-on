package com.example.sso.user.internal.application;

import com.example.sso.user.account.LoginResolutionScope;
import com.example.sso.user.internal.account.domain.AppUser;
import com.example.sso.user.internal.account.domain.AppUserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
    private final EffectiveAuthorityResolver authorityResolver;
    private final LoginResolutionScope loginScope;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        AppUser user = resolve(username)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user: " + username));

        List<SimpleGrantedAuthority> authorities = authorityResolver.authoritiesOf(user).stream()
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
