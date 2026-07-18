package com.example.sso.branding.internal.api;

import com.example.sso.branding.Branding;
import com.example.sso.branding.internal.application.BrandingService;
import com.example.sso.organization.OrganizationRef;
import com.example.sso.organization.OrganizationService;
import com.example.sso.organization.OrganizationStatus;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.tenancy.SubdomainTenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PUBLIC (unauthenticated) branding for the login/MFA SPA: the tenant is selected by the REQUEST HOST (a client
 * can only ever read its own subdomain's branding), and branding is public data anyway. The org is resolved
 * from the host and the read runs inside that org's context so RLS surfaces the tenant's own row (else a
 * bare/unknown host falls back to the platform/built-in default). {@code /api/auth/**} is already permit-all.
 *
 * <p>Host→org is resolved here from the tenancy/organization primitives rather than {@code HostOrgResolver}
 * (which lives in the security module) so that this module does not depend on {@code security} — that edge
 * would close a module cycle (branding→security→…→oidc→branding once the consent page consumes branding). The
 * logic mirrors {@code HostOrgResolver.resolveOrg}: a {@code {slug}.base} host → its ACTIVE org, else empty.
 */
@RestController
@RequestMapping("/api/auth/branding")
@RequiredArgsConstructor
public class BrandingController {

    private final BrandingService service;
    private final SubdomainTenantResolver tenantResolver;
    private final OrganizationService organizations;
    private final OrgContext orgContext;

    @GetMapping
    public Branding get(HttpServletRequest request) {
        UUID orgId = hostOrg(request.getServerName());
        return orgContext.callInOrg(orgId, () -> service.resolve(orgId));
    }

    /** The ACTIVE org this host addresses, or null for the apex / an unknown / suspended tenant. */
    private UUID hostOrg(String host) {
        return tenantResolver.tenantSlug(host)
                .flatMap(slug -> organizations.findBySlug(slug)
                        .filter(org -> org.getStatus() == OrganizationStatus.ACTIVE)
                        .map(OrganizationRef::getId))
                .orElse(null);
    }
}
