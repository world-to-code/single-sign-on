package com.example.sso.security;

import com.example.sso.authpolicy.factor.Factors;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.role.Roles;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds the request's tenant context ({@link OrgContext}) from the authenticated principal so org-scoped
 * reads/writes in the request path are isolated by the caller's tenant. Binds only for a FULLY
 * authenticated session ({@code MFA_COMPLETE}) — a partially-authenticated (password-only) session must not
 * get any tenant/platform scope. A super admin ({@code ROLE_ADMIN}, direct or group-delegated) operates
 * cross-org — the platform context; every other user is scoped to the org they logged into (the {@code ORG_}
 * marker). Cleared at request end; nested {@code OrgContext.callInOrg/callAsPlatform} scopes save-and-restore
 * around this binding. Instantiated (not a {@code @Component}) so it runs only on the app chain it is added
 * to, never auto-registered onto the OAuth2/SAML chains.
 */
public class OrgContextFilter extends OncePerRequestFilter {

    private final OrgContext orgContext;

    public OrgContextFilter(OrgContext orgContext) {
        this.orgContext = orgContext;
    }

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
        // If an earlier filter already bound the tenant context (TenantHostFilter binds it from the request
        // host on the OIDC chain), leave that binding — the host-derived org, which the issuer + signing key
        // follow, must win over the session's org here.
        if (orgContext.currentOrg().isPresent() || orgContext.isPlatform()) {
            return false;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return false;
        }
        Set<String> authorities = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
        // Only a fully-authenticated session gets a tenant/platform binding — never a pre-MFA (password-only)
        // one, which could otherwise carry ROLE_ADMIN and be granted the platform RLS-bypass before MFA.
        if (!authorities.contains(Factors.MFA_COMPLETE)) {
            return false;
        }
        if (authorities.contains(Roles.ADMIN)) {
            orgContext.enterPlatform();
            return true;
        }
        Optional<UUID> org = markerId(authorities, Factors.ORG_PREFIX);
        org.ifPresent(orgContext::bindOrg);
        return org.isPresent();
    }

    private Optional<UUID> markerId(Set<String> authorities, String prefix) {
        return authorities.stream()
                .filter(a -> a.startsWith(prefix))
                .map(a -> a.substring(prefix.length()))
                .map(UUID::fromString)
                .findFirst();
    }
}
