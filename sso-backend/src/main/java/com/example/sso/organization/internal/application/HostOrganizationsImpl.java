package com.example.sso.organization.internal.application;

import com.example.sso.organization.HostOrganizations;
import com.example.sso.organization.OrganizationRef;
import com.example.sso.organization.OrganizationService;
import com.example.sso.organization.OrganizationStatus;
import com.example.sso.tenancy.SubdomainTenantResolver;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class HostOrganizationsImpl implements HostOrganizations {

    private final SubdomainTenantResolver subdomains;
    private final OrganizationService organizations;

    @Override
    public Optional<UUID> activeOrgForHost(String host) {
        return subdomains.tenantSlug(host)
                .flatMap(organizations::findBySlug)
                .filter(org -> org.getStatus() == OrganizationStatus.ACTIVE)
                .map(OrganizationRef::getId);
    }
}
