package com.example.sso.security;

import com.example.sso.organization.HostOrganizations;
import com.example.sso.tenancy.SubdomainTenantResolver;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The security module's view of the host→tenant mapping: what every host-sensitive filter here asks, so the
 * OIDC issuer binding ({@link TenantHostFilter}) and the app-chain tenant-session guard
 * ({@link TenantSessionHostGuard}) can never diverge in how they map a host to a tenant.
 *
 * <p>The mapping itself belongs to {@link HostOrganizations} and is only delegated to here. It used to be
 * written out here, and a second copy lived in the branding controller — which could not import this class
 * without closing a module cycle, so it restated the rule and promised to mirror it. Owning the rule in the
 * module both callers already depend on is what makes that promise unnecessary.
 */
@Component
@RequiredArgsConstructor
public class HostOrgResolver {

    private final SubdomainTenantResolver resolver;
    private final HostOrganizations hostOrganizations;

    /** Whether the host is a configured bare platform base domain (the apex — no tenant is derived from it). */
    public boolean isBaseDomain(String host) {
        return resolver.isBaseDomain(host);
    }

    /** The ACTIVE organization id this host addresses, or empty for the apex / an unknown / suspended tenant. */
    public Optional<UUID> resolveOrg(String host) {
        return hostOrganizations.activeOrgForHost(host);
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
