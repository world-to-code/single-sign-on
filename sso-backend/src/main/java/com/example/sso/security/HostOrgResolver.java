package com.example.sso.security;

import com.example.sso.customer.CustomerService;
import com.example.sso.customer.CustomerStatus;
import com.example.sso.organization.OrganizationRef;
import com.example.sso.organization.OrganizationService;
import com.example.sso.organization.OrganizationStatus;
import com.example.sso.tenancy.SubdomainTenantResolver;
import com.example.sso.tenancy.TenantHost;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Resolves a request HOST to the ACTIVE branch (organization) it addresses — the single source of truth shared
 * by every host-sensitive filter, so the OIDC issuer binding ({@link TenantHostFilter}) and the app-chain
 * tenant-session guard ({@link TenantSessionHostGuard}) can never diverge in how they map a host to a tenant.
 *
 * <p>Two host forms resolve to a branch: the single-label {@code {org}.base} (the org AND its parent customer
 * must be ACTIVE) and the three-level {@code {branch}.{customer}.base} (the customer must be ACTIVE, then the
 * branch within it). A bare platform base domain, an unknown host, or a suspended tenant resolve to empty.
 */
@Component
@RequiredArgsConstructor
public class HostOrgResolver {

    private final SubdomainTenantResolver resolver;
    private final OrganizationService organizations;
    private final CustomerService customers;

    /** Whether the host is a configured bare platform base domain (the apex — no tenant is derived from it). */
    public boolean isBaseDomain(String host) {
        return resolver.isBaseDomain(host);
    }

    /** The ACTIVE branch (organization) id this host addresses, or empty for the apex / an unknown / suspended tenant. */
    public Optional<UUID> resolveOrg(String host) {
        return resolver.resolve(host).map(this::resolveTenantOrg);
    }

    private UUID resolveTenantOrg(TenantHost tenant) {
        if (!tenant.hasCustomer()) {
            // {org}.base — the org AND its parent customer must be ACTIVE, so suspending a customer (고객사) gates
            // all of its branches on the legacy host too, not only on {branch}.{customer}.base.
            return organizations.findBySlug(tenant.orgSlug())
                    .filter(o -> o.getStatus() == OrganizationStatus.ACTIVE)
                    .filter(o -> customers.isActive(o.getCustomerId()))
                    .map(OrganizationRef::getId)
                    .orElse(null);
        }
        // {branch}.{customer}.base — the customer must be ACTIVE, then the branch within it.
        return customers.findBySlug(tenant.customerSlug())
                .filter(customer -> customer.getStatus() == CustomerStatus.ACTIVE)
                .flatMap(customer -> organizations.findBranch(customer.getId(), tenant.orgSlug()))
                .filter(org -> org.getStatus() == OrganizationStatus.ACTIVE)
                .map(OrganizationRef::getId)
                .orElse(null);
    }
}
