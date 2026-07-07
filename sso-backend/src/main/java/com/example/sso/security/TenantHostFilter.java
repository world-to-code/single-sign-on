package com.example.sso.security;

import com.example.sso.tenancy.OrgContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds the request's tenant context from the request HOST on the OIDC chain, so that the per-tenant issuer
 * (host-derived) is backed by the matching tenant's signing key. A single-label {@code {org}.base} host
 * resolves to its organization (the tenant). Runs even for unauthenticated discovery/JWKS.
 *
 * <p>Zero-trust: because the host derives the OIDC issuer, this filter is a strict host allowlist. Only a
 * configured bare (platform) base domain, a subdomain that resolves to an ACTIVE organization, or a branch
 * under an ACTIVE customer is served; any other Host — an unknown/suspended tenant OR an arbitrary domain —
 * is refused with 404, so an attacker-controlled Host cannot mint a forged issuer or poison discovery/JWKS.
 * A bare base domain passes through, leaving the session-based {@link OrgContextFilter} to bind the org.
 * Instantiated (not a {@code @Component}) so it runs only on the OIDC chain it is added to.
 */
public class TenantHostFilter extends OncePerRequestFilter {

    private final HostOrgResolver hostOrgResolver;
    private final OrgContext orgContext;

    public TenantHostFilter(HostOrgResolver hostOrgResolver, OrgContext orgContext) {
        this.hostOrgResolver = hostOrgResolver;
        this.orgContext = orgContext;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String host = request.getServerName();
        Optional<UUID> orgId = hostOrgResolver.resolveOrg(host);
        if (orgId.isEmpty()) {
            if (hostOrgResolver.isBaseDomain(host)) {
                chain.doFilter(request, response); // bare platform host — session-based binding handles it
            } else {
                // Neither a base domain nor a resolvable ACTIVE tenant host — the issuer is host-derived, so
                // refuse rather than mint one for an unrecognised/suspended host.
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
            }
            return;
        }
        orgContext.bindOrg(orgId.get());
        try {
            chain.doFilter(request, response);
        } finally {
            orgContext.clear();
        }
    }
}
