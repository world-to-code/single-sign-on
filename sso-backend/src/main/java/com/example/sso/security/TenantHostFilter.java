package com.example.sso.security;

import com.example.sso.organization.OrganizationRef;
import com.example.sso.organization.OrganizationService;
import com.example.sso.organization.OrganizationStatus;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.tenancy.SubdomainTenantResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds the request's tenant context from the request HOST on the OIDC chain, so that the per-tenant issuer
 * (host-derived) is backed by the matching tenant's signing key: a request to {@code acme.idp.example.com}
 * is resolved to org "acme" and its key signs the token / is published at that host's JWKS. Runs even for
 * the unauthenticated discovery and JWKS endpoints (they must resolve the host's org too).
 *
 * <p>Zero-trust: a subdomain that does NOT resolve to an ACTIVE organization is refused with 404 — the host
 * derives the OIDC issuer, so an unrecognised subdomain must not be able to mint an issuer or fall through
 * to the platform key. A bare (platform) host carries no subdomain and passes through untouched, leaving the
 * session-based {@link OrgContextFilter} to bind the org there. Instantiated (not a {@code @Component}) so it
 * runs only on the OIDC chain it is added to.
 */
public class TenantHostFilter extends OncePerRequestFilter {

    private final SubdomainTenantResolver resolver;
    private final OrganizationService organizations;
    private final OrgContext orgContext;

    public TenantHostFilter(SubdomainTenantResolver resolver, OrganizationService organizations,
                            OrgContext orgContext) {
        this.resolver = resolver;
        this.organizations = organizations;
        this.orgContext = orgContext;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String slug = resolver.tenantSlug(request.getServerName()).orElse(null);
        if (slug == null) {
            chain.doFilter(request, response); // bare platform host — session-based binding handles it
            return;
        }
        UUID orgId = organizations.findBySlug(slug)
                .filter(org -> org.getStatus() == OrganizationStatus.ACTIVE)
                .map(OrganizationRef::getId)
                .orElse(null);
        if (orgId == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND); // unknown/suspended tenant subdomain
            return;
        }
        orgContext.bindOrg(orgId);
        try {
            chain.doFilter(request, response);
        } finally {
            orgContext.clear();
        }
    }
}
