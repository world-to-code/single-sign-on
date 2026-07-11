package com.example.sso.auth.internal.login.application;

import com.example.sso.authpolicy.factor.Factors;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.shared.error.UnauthorizedException;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Resolves the caller from the security context for the auth endpoints, centralizing the two guards
 * that were duplicated across the controller: "must be at least identified" ({@link #require()}) and
 * "must be fully signed in" ({@link #requireMfaComplete()}).
 */
@Component
@RequiredArgsConstructor
public class CurrentUserProvider {

    private final UserService users;

    public Authentication authentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    /** The authenticated (possibly identifier-first) caller; 401 if the session is not authenticated. */
    public UserAccount require() {
        Authentication authentication = authentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new UnauthorizedException();
        }
        return users.findByUsername(authentication.getName())
                .orElseThrow(UnauthorizedException::new);
    }

    /** Self-service management requires a fully-authenticated session, not just an identified one (403 otherwise). */
    public UserAccount requireMfaComplete() {
        UserAccount user = require();
        boolean complete = authentication().getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).anyMatch(Factors.MFA_COMPLETE::equals);
        if (!complete) {
            throw new ForbiddenException("Finish signing in first.");
        }
        return user;
    }
}
