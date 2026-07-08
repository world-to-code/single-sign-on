package com.example.sso.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The app-chain 404 guard: an unknown/suspended tenant subdomain is refused before any app processing, while
 * the platform host and foreign/IP hosts pass through (so health checks are never 404'd).
 */
class TenantUnknownSubdomainGuardTest {

    private final HostOrgResolver hostOrgResolver = mock(HostOrgResolver.class);
    private final TenantUnknownSubdomainGuard guard = new TenantUnknownSubdomainGuard(hostOrgResolver);

    private MockHttpServletRequest requestTo(String host) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName(host);
        return request;
    }

    @Test
    void refusesAnUnknownTenantSubdomainWith404AndStopsTheChain() throws Exception {
        when(hostOrgResolver.isUnknownTenant("nonexistent.localhost")).thenReturn(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        guard.doFilter(requestTo("nonexistent.localhost"), response, chain);

        assertThat(response.getStatus()).isEqualTo(404);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void letsAKnownTenantOrPlatformOrForeignHostThrough() throws Exception {
        when(hostOrgResolver.isUnknownTenant("acme.localhost")).thenReturn(false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        guard.doFilter(requestTo("acme.localhost"), response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }
}
