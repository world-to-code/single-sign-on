package com.example.sso.security;

import com.example.sso.customer.CustomerRef;
import com.example.sso.customer.CustomerService;
import com.example.sso.customer.CustomerStatus;
import com.example.sso.organization.OrganizationRef;
import com.example.sso.organization.OrganizationService;
import com.example.sso.organization.OrganizationStatus;
import com.example.sso.tenancy.SubdomainTenantResolver;
import com.example.sso.tenancy.TenantHost;
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
 * The single source of truth for host→org resolution: a single-label {@code {org}.base} resolves the org
 * directly (org AND its parent customer must be ACTIVE); a three-level {@code {branch}.{customer}.base}
 * resolves the branch only when both the customer and the branch are ACTIVE; a suspended/unknown customer,
 * branch, or arbitrary host resolves to empty; and a bare base domain is reported as such.
 */
@ExtendWith(MockitoExtension.class)
class HostOrgResolverTest {

    @Mock private SubdomainTenantResolver resolver;
    @Mock private OrganizationService organizations;
    @Mock private CustomerService customers;

    @InjectMocks private HostOrgResolver hostOrgResolver;

    private CustomerRef customer(CustomerStatus status, UUID id) {
        CustomerRef c = mock(CustomerRef.class);
        when(c.getStatus()).thenReturn(status);
        if (status == CustomerStatus.ACTIVE) {
            when(c.getId()).thenReturn(id);
        }
        return c;
    }

    private OrganizationRef org(OrganizationStatus status, UUID id) {
        OrganizationRef o = mock(OrganizationRef.class);
        when(o.getStatus()).thenReturn(status);
        lenient().when(o.getId()).thenReturn(id); // not read when an earlier filter (status/customer) rejects
        return o;
    }

    @Test
    void aThreeLevelHostResolvesTheBranchUnderItsActiveCustomer() {
        UUID customerId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        CustomerRef activeCustomer = customer(CustomerStatus.ACTIVE, customerId);
        OrganizationRef activeBranch = org(OrganizationStatus.ACTIVE, orgId);
        when(resolver.resolve("seoul.acme.localhost")).thenReturn(Optional.of(new TenantHost("seoul", "acme")));
        when(customers.findBySlug("acme")).thenReturn(Optional.of(activeCustomer));
        when(organizations.findBranch(customerId, "seoul")).thenReturn(Optional.of(activeBranch));

        assertThat(hostOrgResolver.resolveOrg("seoul.acme.localhost")).contains(orgId);
    }

    @Test
    void aSingleLabelHostResolvesTheOrgWhenBothOrgAndCustomerAreActive() {
        UUID orgId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        OrganizationRef activeOrg = org(OrganizationStatus.ACTIVE, orgId);
        when(activeOrg.getCustomerId()).thenReturn(customerId);
        when(resolver.resolve("acme.localhost")).thenReturn(Optional.of(new TenantHost("acme", null)));
        when(organizations.findBySlug("acme")).thenReturn(Optional.of(activeOrg));
        when(customers.isActive(customerId)).thenReturn(true);

        assertThat(hostOrgResolver.resolveOrg("acme.localhost")).contains(orgId);
    }

    @Test
    void aSingleLabelBranchOfASuspendedCustomerResolvesToEmpty() {
        // The kill-switch: suspending a customer gates its branches on the legacy {org}.base host too.
        UUID orgId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        OrganizationRef activeOrg = org(OrganizationStatus.ACTIVE, orgId);
        when(activeOrg.getCustomerId()).thenReturn(customerId);
        when(resolver.resolve("acme.localhost")).thenReturn(Optional.of(new TenantHost("acme", null)));
        when(organizations.findBySlug("acme")).thenReturn(Optional.of(activeOrg));
        when(customers.isActive(customerId)).thenReturn(false); // parent customer suspended

        assertThat(hostOrgResolver.resolveOrg("acme.localhost")).isEmpty();
    }

    @Test
    void aSuspendedCustomerResolvesToEmpty() {
        CustomerRef suspended = customer(CustomerStatus.SUSPENDED, null);
        when(resolver.resolve("seoul.acme.localhost")).thenReturn(Optional.of(new TenantHost("seoul", "acme")));
        when(customers.findBySlug("acme")).thenReturn(Optional.of(suspended));

        assertThat(hostOrgResolver.resolveOrg("seoul.acme.localhost")).isEmpty();
    }

    @Test
    void anUnknownBranchUnderAKnownCustomerResolvesToEmpty() {
        UUID customerId = UUID.randomUUID();
        CustomerRef activeCustomer = customer(CustomerStatus.ACTIVE, customerId);
        when(resolver.resolve("nope.acme.localhost")).thenReturn(Optional.of(new TenantHost("nope", "acme")));
        when(customers.findBySlug("acme")).thenReturn(Optional.of(activeCustomer));
        when(organizations.findBranch(customerId, "nope")).thenReturn(Optional.empty());

        assertThat(hostOrgResolver.resolveOrg("nope.acme.localhost")).isEmpty();
    }

    @Test
    void anArbitraryHostThatIsNotATenantResolvesToEmpty() {
        when(resolver.resolve("evil.com")).thenReturn(Optional.empty());

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
