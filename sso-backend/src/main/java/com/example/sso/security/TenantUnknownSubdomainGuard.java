package com.example.sso.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * App-chain twin of {@link TenantHostFilter}: a request to an unknown or suspended tenant SUBDOMAIN
 * ({@code {slug}.base} that resolves to no ACTIVE org) is refused with 404 across the WHOLE app surface —
 * the SPA shell, static assets and {@code /api/**} — not only the OIDC endpoints the AS chain already guards.
 * So navigating to {@code nonexistent.localhost:9000} yields a real 404 rather than the app shell.
 *
 * <p>The bare platform host and foreign/IP hosts (which carry no tenant label — e.g. a {@code 127.0.0.1} or
 * pod-IP health check) pass through untouched; only a subdomain-shaped host with no active tenant 404s.
 * Instantiated (not a {@code @Component}) so it runs only on the chain it is explicitly added to.
 */
public class TenantUnknownSubdomainGuard extends OncePerRequestFilter {

    private final HostOrgResolver hostOrgResolver;

    public TenantUnknownSubdomainGuard(HostOrgResolver hostOrgResolver) {
        this.hostOrgResolver = hostOrgResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (hostOrgResolver.isUnknownTenant(request.getServerName())) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        chain.doFilter(request, response);
    }
}
