package com.example.sso.security;

import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.session.IpRuleSpec;
import com.example.sso.session.SessionPolicyDetails;
import com.example.sso.session.SessionPolicyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link PolicyIpAccessFilter} — per-policy, post-authentication network access. Proves a
 * denied network is 403'd (audited, chain not invoked), an allowed network proceeds, and an unauthenticated
 * request is passed straight through (no user ⇒ no policy). Registered on both security chains, so this is
 * the single point that gates OIDC /oauth2/authorize as well as /api/**.
 */
@ExtendWith(MockitoExtension.class)
class PolicyIpAccessFilterTest {

    @Mock private SessionPolicyService policyService;
    @Mock private AuditService audit;
    @Mock private SessionPolicyDetails policy;

    private PolicyIpAccessFilter filter;

    @BeforeEach
    void setUp() {
        filter = new PolicyIpAccessFilter(policyService, audit);
        lenient().when(policyService.resolveForUsername("alice")).thenReturn(policy);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, List.of()));
    }

    private MockHttpServletRequest request(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/oauth2/authorize");
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    @Test
    void aDeniedNetworkIsRefusedWith403AndAudited() throws Exception {
        authenticate();
        when(policy.getIpRules()).thenReturn(List.of(new IpRuleSpec("203.0.113.0/24", "BLOCK", 0)));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request("203.0.113.9"), response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        verify(audit).record(AuditType.IP_BLOCKED, "alice", false);
        verify(chain, never()).doFilter(any(), any()); // OIDC authorize NOT reached from a blocked network
    }

    @Test
    void anAllowedNetworkProceeds() throws Exception {
        authenticate();
        when(policy.getIpRules()).thenReturn(
                List.of(new IpRuleSpec("10.0.0.0/8", "ALLOW", 0), new IpRuleSpec("0.0.0.0/0", "BLOCK", 1)));
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request("10.2.3.4"), new MockHttpServletResponse(), chain);

        verify(chain).doFilter(any(), any());
    }

    @Test
    void anUnauthenticatedRequestIsPassedThroughWithoutResolvingAPolicy() throws Exception {
        // no authentication in the context
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request("203.0.113.9"), new MockHttpServletResponse(), chain);

        verify(chain).doFilter(any(), any());
        verifyNoInteractions(policyService, audit);
    }
}
