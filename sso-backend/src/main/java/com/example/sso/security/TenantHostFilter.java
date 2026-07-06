package com.example.sso.security;

import com.example.sso.customer.CustomerService;
import com.example.sso.customer.CustomerStatus;
import com.example.sso.organization.OrganizationRef;
import com.example.sso.organization.OrganizationService;
import com.example.sso.organization.OrganizationStatus;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.tenancy.SubdomainTenantResolver;
import com.example.sso.tenancy.TenantHost;
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
 * (host-derived) is backed by the matching tenant's signing key. Two host forms resolve to the branch
 * (organization): the established single-label {@code {org}.base}, and the three-level
 * {@code {branch}.{customer}.base} where a branch is addressed within its parent customer (고객사) — so
 * branches under different customers may share a slug. Runs even for unauthenticated discovery/JWKS.
 *
 * <p>Zero-trust: because the host derives the OIDC issuer, this filter is a strict host allowlist. Only a
 * configured bare (platform) base domain, a subdomain that resolves to an ACTIVE organization, or a branch
 * under an ACTIVE customer is served; any other Host — an unknown/suspended tenant OR an arbitrary domain —
 * is refused with 404, so an attacker-controlled Host cannot mint a forged issuer or poison discovery/JWKS.
 * A bare base domain passes through, leaving the session-based {@link OrgContextFilter} to bind the org.
 * Instantiated (not a {@code @Component}) so it runs only on the OIDC chain it is added to.
 */
public class TenantHostFilter extends OncePerRequestFilter {

    private final SubdomainTenantResolver resolver;
    private final OrganizationService organizations;
    private final CustomerService customers;
    private final OrgContext orgContext;

    public TenantHostFilter(SubdomainTenantResolver resolver, OrganizationService organizations,
                            CustomerService customers, OrgContext orgContext) {
        this.resolver = resolver;
        this.organizations = organizations;
        this.customers = customers;
        this.orgContext = orgContext;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String host = request.getServerName();
        TenantHost tenant = resolver.resolve(host).orElse(null);
        if (tenant == null) {
            if (resolver.isBaseDomain(host)) {
                chain.doFilter(request, response); // bare platform host — session-based binding handles it
            } else {
                // Neither a base domain nor a resolvable tenant host — the issuer is host-derived, so refuse
                // rather than mint one for an unrecognised host.
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
            }
            return;
        }
        UUID orgId = resolveOrg(tenant);
        if (orgId == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND); // unknown/suspended tenant (or customer)
            return;
        }
        orgContext.bindOrg(orgId);
        try {
            chain.doFilter(request, response);
        } finally {
            orgContext.clear();
        }
    }

    /** Resolve the host's labels to an ACTIVE branch (organization) id, or null. */
    private UUID resolveOrg(TenantHost tenant) {
        if (!tenant.hasCustomer()) {
            return activeOrgId(organizations.findBySlug(tenant.orgSlug())); // {org}.base — direct org lookup
        }
        // {branch}.{customer}.base — the customer must be ACTIVE, then the branch within it.
        return customers.findBySlug(tenant.customerSlug())
                .filter(customer -> customer.getStatus() == CustomerStatus.ACTIVE)
                .flatMap(customer -> organizations.findBranch(customer.getId(), tenant.orgSlug()))
                .filter(org -> org.getStatus() == OrganizationStatus.ACTIVE)
                .map(OrganizationRef::getId)
                .orElse(null);
    }

    // The single-label {org}.base path — the org AND its parent customer must be ACTIVE, so suspending a
    // customer (고객사) gates all of its branches on the legacy host too, not only on {branch}.{customer}.base.
    private UUID activeOrgId(Optional<OrganizationRef> org) {
        return org.filter(o -> o.getStatus() == OrganizationStatus.ACTIVE)
                .filter(o -> customers.isActive(o.getCustomerId()))
                .map(OrganizationRef::getId)
                .orElse(null);
    }
}
