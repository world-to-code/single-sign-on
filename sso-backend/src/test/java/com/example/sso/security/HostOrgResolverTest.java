package com.example.sso.security;

import com.example.sso.organization.HostOrganizations;
import com.example.sso.tenancy.SubdomainTenantResolver;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * What this class still decides for itself.
 *
 * <p>Host→ACTIVE-org is no longer among it: that rule moved to {@code organization.HostOrganizations}, which
 * carries its own matrix (active, suspended, unknown, not a tenant address), because a private copy of it also
 * lived in the branding module and the two were kept in step only by a comment. Re-asserting the moved rule
 * through a mock here would prove nothing about either, so what remains is the delegation and the one question
 * this class genuinely answers.
 *
 * <p>That question is {@code isUnknownTenant}, and it had no test at all — it decides whether a host 404s, so
 * getting it wrong either hides a live tenant or takes down health checks arriving at a bare IP. It is not the
 * same as "resolves to no org": a foreign host resolves to no org either, and must NOT 404.
 */
@ExtendWith(MockitoExtension.class)
class HostOrgResolverTest {

    @Mock private SubdomainTenantResolver resolver;
    @Mock private HostOrganizations hostOrganizations;

    @InjectMocks private HostOrgResolver hostOrgResolver;

    @Test
    void resolveOrgDelegatesToTheOwnerOfTheRule() {
        UUID orgId = UUID.randomUUID();
        when(hostOrganizations.activeOrgForHost("acme.localhost")).thenReturn(Optional.of(orgId));

        assertThat(hostOrgResolver.resolveOrg("acme.localhost")).contains(orgId);
    }

    @Test
    void isBaseDomainDelegatesToTheResolver() {
        when(resolver.isBaseDomain("localhost")).thenReturn(true);
        when(resolver.isBaseDomain("evil.com")).thenReturn(false);

        assertThat(hostOrgResolver.isBaseDomain("localhost")).isTrue();
        assertThat(hostOrgResolver.isBaseDomain("evil.com")).isFalse();
    }

    /** A tenant-shaped host naming no servable org — unknown or suspended. This is the 404. */
    @Test
    void aTenantShapedHostWithNoActiveOrgIsAnUnknownTenant() {
        when(resolver.tenantSlug("gone.localhost")).thenReturn(Optional.of("gone"));
        when(hostOrganizations.activeOrgForHost("gone.localhost")).thenReturn(Optional.empty());

        assertThat(hostOrgResolver.isUnknownTenant("gone.localhost")).isTrue();
    }

    @Test
    void aTenantShapedHostWithAnActiveOrgIsNotUnknown() {
        when(resolver.tenantSlug("acme.localhost")).thenReturn(Optional.of("acme"));
        when(hostOrganizations.activeOrgForHost("acme.localhost")).thenReturn(Optional.of(UUID.randomUUID()));

        assertThat(hostOrgResolver.isUnknownTenant("acme.localhost")).isFalse();
    }

    /**
     * The distinction the method exists for: an apex, a foreign name or a bare IP carries no tenant label, so it
     * resolves to no org WITHOUT being an unknown tenant. Answering 404 here would take out a load balancer's
     * health check, which arrives at the IP.
     */
    @Test
    void aHostCarryingNoTenantLabelIsNotAnUnknownTenant() {
        when(resolver.tenantSlug("10.0.0.4")).thenReturn(Optional.empty());

        assertThat(hostOrgResolver.isUnknownTenant("10.0.0.4")).isFalse();
    }
}
