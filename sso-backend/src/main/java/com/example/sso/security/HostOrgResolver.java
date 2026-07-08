package com.example.sso.security;

import com.example.sso.organization.OrganizationRef;
import com.example.sso.organization.OrganizationService;
import com.example.sso.organization.OrganizationStatus;
import com.example.sso.tenancy.SubdomainTenantResolver;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Resolves a request HOST to the ACTIVE organization (the tenant) it addresses — the single source of truth
 * shared by every host-sensitive filter, so the OIDC issuer binding ({@link TenantHostFilter}) and the
 * app-chain tenant-session guard ({@link TenantSessionHostGuard}) can never diverge in how they map a host to
 * a tenant. A {@code {org}.base} host resolves to its organization when ACTIVE; a bare platform base domain, an
 * unknown host, or a suspended organization resolve to empty.
 */
@Component
@RequiredArgsConstructor
public class HostOrgResolver {

    private final SubdomainTenantResolver resolver;
    private final OrganizationService organizations;

    /** Whether the host is a configured bare platform base domain (the apex — no tenant is derived from it). */
    public boolean isBaseDomain(String host) {
        return resolver.isBaseDomain(host);
    }

    /** The ACTIVE organization id this host addresses, or empty for the apex / an unknown / suspended tenant. */
    public Optional<UUID> resolveOrg(String host) {
        return resolver.tenantSlug(host).flatMap(slug -> organizations.findBySlug(slug)
                .filter(o -> o.getStatus() == OrganizationStatus.ACTIVE)
                .map(OrganizationRef::getId));
    }

    /**
     * Whether the host is a subdomain-SHAPED tenant host ({@code {slug}.base}) that resolves to NO active org —
     * an unknown or suspended tenant. Distinguishes it from the bare platform host and from a foreign/IP host
     * (which carries no tenant label): only the former should 404, so health checks at an IP still pass.
     */
    public boolean isUnknownTenant(String host) {
        return resolver.tenantSlug(host).isPresent() && resolveOrg(host).isEmpty();
    }
}
