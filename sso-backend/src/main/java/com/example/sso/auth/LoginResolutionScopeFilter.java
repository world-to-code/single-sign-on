package com.example.sso.auth;

import com.example.sso.auth.internal.login.application.PreAuthOrgSession;
import com.example.sso.authpolicy.Factors;
import com.example.sso.user.LoginResolutionScope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds the login's organization (the tenant) to the {@link LoginResolutionScope} for the duration of a
 * sign-in request, so that resolving a user by username — the password provider's {@code loadUserByUsername},
 * factor-state lookups, and lockout — targets the organization being signed into rather than a same-named user
 * in another organization. Only active during the LOGIN phase: before MFA completes, {@code OrgContext} is not
 * yet bound to the session's org, so this fills that gap; a completed session resolves within its bound
 * {@code OrgContext} instead.
 */
@Component
@RequiredArgsConstructor
public class LoginResolutionScopeFilter extends OncePerRequestFilter {

    private final PreAuthOrgSession preAuthOrg;
    private final LoginResolutionScope loginScope;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<UUID> loginOrg = mfaComplete() ? Optional.empty() : preAuthOrg.orgId(request);
        if (loginOrg.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        try (var ignored = loginScope.open(loginOrg.get())) {
            chain.doFilter(request, response);
        }
    }

    private boolean mfaComplete() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).anyMatch(Factors.MFA_COMPLETE::equals);
    }
}
