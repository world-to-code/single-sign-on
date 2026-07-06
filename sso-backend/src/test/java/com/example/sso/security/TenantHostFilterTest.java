package com.example.sso.security;

import com.example.sso.customer.CustomerRef;
import com.example.sso.customer.CustomerService;
import com.example.sso.customer.CustomerStatus;
import com.example.sso.organization.OrganizationRef;
import com.example.sso.organization.OrganizationService;
import com.example.sso.organization.OrganizationStatus;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.tenancy.SubdomainTenantResolver;
import com.example.sso.tenancy.TenantHost;
import jakarta.servlet.FilterChain;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the host allowlist + tenant binding: a single-label {@code {org}.base} binds the org
 * directly; a three-level {@code {branch}.{customer}.base} binds the branch only when both the customer and
 * the branch are ACTIVE; a suspended/unknown customer, branch, or arbitrary host is refused with 404 without
 * binding; a bare base domain passes through untouched.
 */
@ExtendWith(MockitoExtension.class)
class TenantHostFilterTest {

    @Mock private SubdomainTenantResolver resolver;
    @Mock private OrganizationService organizations;
    @Mock private CustomerService customers;
    @Mock private OrgContext orgContext;

    @InjectMocks private TenantHostFilter filter;

    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    @Mock private FilterChain chain;

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
        if (status == OrganizationStatus.ACTIVE) {
            when(o.getId()).thenReturn(id);
        }
        return o;
    }

    @Test
    void aThreeLevelHostBindsTheBranchUnderItsActiveCustomer() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        request.setServerName("seoul.acme.localhost");
        CustomerRef activeCustomer = customer(CustomerStatus.ACTIVE, customerId); // build mocks first (no nested stubbing)
        OrganizationRef activeBranch = org(OrganizationStatus.ACTIVE, orgId);
        when(resolver.resolve("seoul.acme.localhost")).thenReturn(Optional.of(new TenantHost("seoul", "acme")));
        when(customers.findBySlug("acme")).thenReturn(Optional.of(activeCustomer));
        when(organizations.findBranch(customerId, "seoul")).thenReturn(Optional.of(activeBranch));

        filter.doFilter(request, response, chain);

        verify(orgContext).bindOrg(orgId);
        verify(chain).doFilter(request, response);
        verify(orgContext).clear();
    }

    @Test
    void aSingleLabelHostBindsTheOrgDirectly() throws Exception {
        UUID orgId = UUID.randomUUID();
        request.setServerName("acme.localhost");
        OrganizationRef activeOrg = org(OrganizationStatus.ACTIVE, orgId);
        when(resolver.resolve("acme.localhost")).thenReturn(Optional.of(new TenantHost("acme", null)));
        when(organizations.findBySlug("acme")).thenReturn(Optional.of(activeOrg));

        filter.doFilter(request, response, chain);

        verify(orgContext).bindOrg(orgId);
        verify(chain).doFilter(request, response);
    }

    @Test
    void aSuspendedCustomerIsRefusedWithoutBinding() throws Exception {
        request.setServerName("seoul.acme.localhost");
        CustomerRef suspended = customer(CustomerStatus.SUSPENDED, null);
        when(resolver.resolve("seoul.acme.localhost")).thenReturn(Optional.of(new TenantHost("seoul", "acme")));
        when(customers.findBySlug("acme")).thenReturn(Optional.of(suspended));

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(404);
        verify(orgContext, never()).bindOrg(any());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void anUnknownBranchUnderAKnownCustomerIsRefused() throws Exception {
        UUID customerId = UUID.randomUUID();
        request.setServerName("nope.acme.localhost");
        CustomerRef activeCustomer = customer(CustomerStatus.ACTIVE, customerId);
        when(resolver.resolve("nope.acme.localhost")).thenReturn(Optional.of(new TenantHost("nope", "acme")));
        when(customers.findBySlug("acme")).thenReturn(Optional.of(activeCustomer));
        when(organizations.findBranch(customerId, "nope")).thenReturn(Optional.empty());

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(404);
        verify(orgContext, never()).bindOrg(any());
    }

    @Test
    void anArbitraryHostThatIsNeitherBaseNorTenantIsRefused() throws Exception {
        request.setServerName("evil.com");
        when(resolver.resolve("evil.com")).thenReturn(Optional.empty());
        when(resolver.isBaseDomain("evil.com")).thenReturn(false);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(404);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void aBareBaseDomainPassesThroughForSessionBinding() throws Exception {
        request.setServerName("localhost");
        when(resolver.resolve("localhost")).thenReturn(Optional.empty());
        when(resolver.isBaseDomain("localhost")).thenReturn(true);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(orgContext, never()).bindOrg(any());
    }
}
