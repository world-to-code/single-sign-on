package com.example.sso.security;

import com.example.sso.tenancy.OrgContext;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the OIDC-chain host binding: the filter delegates host→org resolution to
 * {@link HostOrgResolver} (whose ACTIVE-org/customer matrix is tested in {@link HostOrgResolverTest}) and
 * then binds the org, passes a bare base domain through for session binding, or refuses an unknown/suspended
 * tenant host with 404 — so a host-derived OIDC issuer is only minted for a recognised, active tenant.
 */
@ExtendWith(MockitoExtension.class)
class TenantHostFilterTest {

    @Mock private HostOrgResolver hostOrgResolver;
    @Mock private OrgContext orgContext;

    @InjectMocks private TenantHostFilter filter;

    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    @Mock private FilterChain chain;

    @Test
    void bindsTheResolvedOrgThenClears() throws Exception {
        UUID orgId = UUID.randomUUID();
        request.setServerName("seoul.acme.localhost");
        when(hostOrgResolver.resolveOrg("seoul.acme.localhost")).thenReturn(Optional.of(orgId));

        filter.doFilter(request, response, chain);

        verify(orgContext).bindOrg(orgId);
        verify(chain).doFilter(request, response);
        verify(orgContext).clear();
    }

    @Test
    void aBareBaseDomainPassesThroughForSessionBinding() throws Exception {
        request.setServerName("localhost");
        when(hostOrgResolver.resolveOrg("localhost")).thenReturn(Optional.empty());
        when(hostOrgResolver.isBaseDomain("localhost")).thenReturn(true);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(orgContext, never()).bindOrg(any());
    }

    @Test
    void anUnknownOrSuspendedTenantHostIsRefusedWithoutBinding() throws Exception {
        request.setServerName("evil.com");
        when(hostOrgResolver.resolveOrg("evil.com")).thenReturn(Optional.empty());
        when(hostOrgResolver.isBaseDomain("evil.com")).thenReturn(false);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(404);
        verify(orgContext, never()).bindOrg(any());
        verify(chain, never()).doFilter(any(), any());
    }
}
