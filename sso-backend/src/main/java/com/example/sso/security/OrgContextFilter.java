package com.example.sso.security;

import com.example.sso.authpolicy.Factors;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.Roles;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds the request's tenant context ({@link OrgContext}) from the authenticated principal so org-scoped
 * reads/writes in the request path are isolated by the caller's tenant. A super admin ({@code ROLE_ADMIN},
 * direct or group-delegated) operates cross-org — the platform context; every other authenticated user is
 * scoped to the organization they logged into (the {@code ORG_} marker). Cleared at request end; nested
 * {@code OrgContext.callInOrg/callAsPlatform} scopes save-and-restore around this binding.
 */
@Component
@RequiredArgsConstructor
public class OrgContextFilter extends OncePerRequestFilter {

    private final OrgContext orgContext;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean bound = bindContext();
        try {
            chain.doFilter(request, response);
        } finally {
            if (bound) {
                orgContext.clear();
            }
        }
    }

    private boolean bindContext() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return false;
        }
        boolean superAdmin = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).anyMatch(Roles.ADMIN::equals);
        if (superAdmin) {
            orgContext.enterPlatform();
            return true;
        }
        Optional<UUID> org = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith(Factors.ORG_PREFIX))
                .map(a -> a.substring(Factors.ORG_PREFIX.length()))
                .map(UUID::fromString)
                .findFirst();
        org.ifPresent(orgContext::bindOrg);
        return org.isPresent();
    }
}
