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
 * <p>Zero-trust: because the host derives the OIDC issuer, this filter is a strict host allowlist. Only a
 * configured bare (platform) base domain or a subdomain that resolves to an ACTIVE organization is served;
 * any other Host — an unknown tenant subdomain OR an arbitrary domain like {@code evil.com} — is refused
 * with 404, so an attacker-controlled Host header cannot mint a forged issuer or poison the discovery/JWKS
 * documents. A bare base domain passes through, leaving the session-based {@link OrgContextFilter} to bind
 * the org there. Instantiated (not a {@code @Component}) so it runs only on the OIDC chain it is added to.
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
        String host = request.getServerName();
        String slug = resolver.tenantSlug(host).orElse(null);
        if (slug == null) {
            if (resolver.isBaseDomain(host)) {
                chain.doFilter(request, response); // bare platform host — session-based binding handles it
            } else {
                // Not a base domain and not a tenant subdomain (e.g. an arbitrary/spoofed Host) — the issuer
                // is host-derived, so refuse rather than mint one for an unrecognised host.
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
            }
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
