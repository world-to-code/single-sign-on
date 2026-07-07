package com.example.sso.security;

import com.example.sso.organization.OrganizationRef;
import com.example.sso.organization.OrganizationService;
import com.example.sso.organization.OrganizationStatus;
import com.example.sso.tenancy.SubdomainTenantResolver;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The single source of truth for host→org resolution now that the organization IS the tenant: a
 * {@code {org}.base} host resolves the org when ACTIVE; a suspended/unknown org or an arbitrary host resolves
 * to empty; and a bare base domain is reported as such.
 */
@ExtendWith(MockitoExtension.class)
class HostOrgResolverTest {

    @Mock private SubdomainTenantResolver resolver;
    @Mock private OrganizationService organizations;

    @InjectMocks private HostOrgResolver hostOrgResolver;

    private OrganizationRef org(OrganizationStatus status, UUID id) {
        OrganizationRef o = mock(OrganizationRef.class);
        when(o.getStatus()).thenReturn(status);
        lenient().when(o.getId()).thenReturn(id); // not read when the status filter rejects
        return o;
    }

    @Test
    void aTenantHostResolvesTheOrgWhenActive() {
        UUID orgId = UUID.randomUUID();
        OrganizationRef active = org(OrganizationStatus.ACTIVE, orgId); // build the mock first (no nested stubbing)
        when(resolver.tenantSlug("acme.localhost")).thenReturn(Optional.of("acme"));
        when(organizations.findBySlug("acme")).thenReturn(Optional.of(active));

        assertThat(hostOrgResolver.resolveOrg("acme.localhost")).contains(orgId);
    }

    @Test
    void aSuspendedOrgResolvesToEmpty() {
        OrganizationRef suspended = org(OrganizationStatus.SUSPENDED, null);
        when(resolver.tenantSlug("acme.localhost")).thenReturn(Optional.of("acme"));
        when(organizations.findBySlug("acme")).thenReturn(Optional.of(suspended));

        assertThat(hostOrgResolver.resolveOrg("acme.localhost")).isEmpty();
    }

    @Test
    void anUnknownOrgResolvesToEmpty() {
        when(resolver.tenantSlug("nope.localhost")).thenReturn(Optional.of("nope"));
        when(organizations.findBySlug("nope")).thenReturn(Optional.empty());

        assertThat(hostOrgResolver.resolveOrg("nope.localhost")).isEmpty();
    }

    @Test
    void anArbitraryHostThatIsNotATenantResolvesToEmpty() {
        when(resolver.tenantSlug("evil.com")).thenReturn(Optional.empty());

        assertThat(hostOrgResolver.resolveOrg("evil.com")).isEmpty();
    }

    @Test
    void isBaseDomainDelegatesToTheResolver() {
        when(resolver.isBaseDomain("localhost")).thenReturn(true);
        when(resolver.isBaseDomain("evil.com")).thenReturn(false);

        assertThat(hostOrgResolver.isBaseDomain("localhost")).isTrue();
        assertThat(hostOrgResolver.isBaseDomain("evil.com")).isFalse();
    }
}
