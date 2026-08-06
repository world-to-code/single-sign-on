package com.example.sso.organization;

import com.example.sso.organization.internal.application.HostOrganizationsImpl;
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
 * "Which organization does this host address, and may it be served?" — the one place that answers it.
 *
 * <p>The rule lived in two places before: {@code security.HostOrgResolver} and a private copy in the branding
 * controller, which could not import it without closing a module cycle. The copy carried a comment promising
 * the two would be kept in step by hand, and nothing held them to it: each path had its own tests, so either
 * could have gained a condition the other never learned about.
 *
 * <p>The matrix below is that question in full: an active tenant resolves, and every other way a host can fail
 * to name a servable tenant — suspended, unknown, not a tenant host at all — resolves to empty rather than to
 * something. Each is a separate assertion because they fail for different reasons: a rejection test that does
 * not say WHY passes whether or not the status was ever checked.
 */
@ExtendWith(MockitoExtension.class)
class HostOrganizationsImplTest {

    @Mock
    private SubdomainTenantResolver subdomains;
    @Mock
    private OrganizationService organizations;
    @InjectMocks
    private HostOrganizationsImpl hostOrganizations;

    private OrganizationRef tenant(UUID id, OrganizationStatus status) {
        OrganizationRef ref = mock(OrganizationRef.class);
        when(ref.getStatus()).thenReturn(status);
        lenient().when(ref.getId()).thenReturn(id); // not read once the status filter rejects
        return ref;
    }

    @Test
    void aTenantHostResolvesTheOrgWhenActive() {
        UUID orgId = UUID.randomUUID();
        OrganizationRef active = tenant(orgId, OrganizationStatus.ACTIVE);
        when(subdomains.tenantSlug("acme.localhost")).thenReturn(Optional.of("acme"));
        when(organizations.findBySlug("acme")).thenReturn(Optional.of(active));

        assertThat(hostOrganizations.activeOrgForHost("acme.localhost")).contains(orgId);
    }

    /** The case the whole rule exists for: a suspended tenant is not served, on any path that asks. */
    @Test
    void aSuspendedTenantResolvesToEmpty() {
        OrganizationRef suspended = tenant(UUID.randomUUID(), OrganizationStatus.SUSPENDED);
        when(subdomains.tenantSlug("acme.localhost")).thenReturn(Optional.of("acme"));
        when(organizations.findBySlug("acme")).thenReturn(Optional.of(suspended));

        assertThat(hostOrganizations.activeOrgForHost("acme.localhost")).isEmpty();
    }

    @Test
    void aSlugThatNamesNoOrganizationResolvesToEmpty() {
        when(subdomains.tenantSlug("nope.localhost")).thenReturn(Optional.of("nope"));
        when(organizations.findBySlug("nope")).thenReturn(Optional.empty());

        assertThat(hostOrganizations.activeOrgForHost("nope.localhost")).isEmpty();
    }

    /** The apex and any foreign host: not a tenant address, so nothing is looked up at all. */
    @Test
    void aHostThatIsNotATenantAddressResolvesToEmpty() {
        when(subdomains.tenantSlug("evil.com")).thenReturn(Optional.empty());

        assertThat(hostOrganizations.activeOrgForHost("evil.com")).isEmpty();
    }
}
