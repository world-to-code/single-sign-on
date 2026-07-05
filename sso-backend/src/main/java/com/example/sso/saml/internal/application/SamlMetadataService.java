package com.example.sso.saml.internal.application;

import com.example.sso.organization.OrganizationRef;
import com.example.sso.organization.OrganizationService;
import com.example.sso.organization.OrganizationStatus;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.tenancy.SubdomainTenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Builds the IdP SAML metadata for the request host: the entityID and endpoints are host-derived, and the
 * document is built in that host's tenant context so it carries that tenant's own signing certificate (a
 * bare platform host uses the global one). An unrecognised host is refused — the host derives the entityID,
 * so it must not publish a forged one.
 */
@Service
@RequiredArgsConstructor
public class SamlMetadataService {

    private final SamlMetadataBuilder builder;
    private final SamlEntityId samlEntityId;
    private final SubdomainTenantResolver subdomainResolver;
    private final OrganizationService organizations;
    private final OrgContext orgContext;

    public String metadataFor(HttpServletRequest request) {
        String entityId = samlEntityId.resolve(request);
        UUID org = hostOrg(request.getServerName());
        return org == null
                ? builder.buildMetadata(entityId) // bare platform host — the global credential
                : orgContext.callInOrg(org, () -> builder.buildMetadata(entityId)); // the tenant's own cert
    }

    private UUID hostOrg(String host) {
        String slug = subdomainResolver.tenantSlug(host).orElse(null);
        if (slug == null) {
            if (subdomainResolver.isBaseDomain(host)) {
                return null;
            }
            throw new NotFoundException("Unknown host");
        }
        return organizations.findBySlug(slug)
                .filter(org -> org.getStatus() == OrganizationStatus.ACTIVE)
                .map(OrganizationRef::getId)
                .orElseThrow(() -> new NotFoundException("No such organization"));
    }
}
