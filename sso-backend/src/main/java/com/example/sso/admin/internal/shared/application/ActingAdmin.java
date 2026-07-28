package com.example.sso.admin.internal.shared.application;

import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import com.example.sso.user.role.Roles;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Who is making this request, and what they hold — the one thing every admin policy needs before it can decide
 * anything, and the one thing none of them should each work out for themselves.
 *
 * <p>The subtle part is {@link #id}. The acting principal is resolved by their OWN identity, not by the
 * organization they have drilled into: a platform super-admin is a GLOBAL account ({@code org_id} null,
 * carrying {@code ROLE_ADMIN}), so they are looked up globally — otherwise a same-named user planted in the
 * drilled-into org, which per-org uniqueness permits, would be mistaken for the actor. A tenant admin only ever
 * exists in their own bound org, so the ordinary lookup is right for them.
 *
 * <p>Everything here answers {@link Optional#empty} or an empty set for an unauthenticated caller, so a policy
 * built on it fails CLOSED by construction rather than by each caller remembering to.
 */
@Component
@RequiredArgsConstructor
class ActingAdmin {

    private final UserService userService;

    /** The acting administrator's account id, or empty when nobody is authenticated. */
    Optional<UUID> id() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        boolean platformAdmin = authorityNames(authentication).contains(Roles.ADMIN);
        return (platformAdmin
                ? userService.findByUsernameInOrg(authentication.getName(), null)
                : userService.findByUsername(authentication.getName()))
                .map(UserAccount::getId);
    }

    /** The authority strings the acting admin currently holds (role names, permissions, session markers). */
    Set<String> authorities() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? Set.of() : authorityNames(authentication);
    }

    /** The authenticated name, or {@code null} when nobody is authenticated. */
    String username() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? null : authentication.getName();
    }

    private Set<String> authorityNames(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }
}
