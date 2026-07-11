package com.example.sso.security;

import com.example.sso.authpolicy.factor.Factors;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.Roles;
import jakarta.servlet.FilterChain;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link OrgContextFilter}: a super admin gets the platform context, a regular user is bound
 * to the org from their {@code ORG_} marker, and an unauthenticated/marker-less request binds nothing. The
 * bound context is always cleared after the chain.
 */
class OrgContextFilterTest {

    private final OrgContext orgContext = mock(OrgContext.class);
    private final OrgContextFilter filter = new OrgContextFilter(orgContext);
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final FilterChain chain = mock(FilterChain.class);

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aSuperAdminGetsThePlatformContext() throws Exception {
        authenticate("admin", Roles.ADMIN, Factors.MFA_COMPLETE, Factors.ORG_PREFIX + UUID.randomUUID());

        filter.doFilter(request, response, chain);

        verify(orgContext).enterPlatform(); // super admin operates cross-org, ignoring the login org
        verify(orgContext, never()).bindOrg(any());
        verify(orgContext).clear();
        verify(chain).doFilter(request, response);
    }

    @Test
    void aRegularUserIsBoundToTheirLoginOrg() throws Exception {
        UUID org = UUID.randomUUID();
        authenticate("bob", "ROLE_USER", Factors.MFA_COMPLETE, Factors.ORG_PREFIX + org);

        filter.doFilter(request, response, chain);

        verify(orgContext).bindOrg(org);
        verify(orgContext, never()).enterPlatform();
        verify(orgContext).clear();
    }

    @Test
    void anUnauthenticatedRequestBindsNothing() throws Exception {
        filter.doFilter(request, response, chain);

        verify(orgContext, never()).bindOrg(any());
        verify(orgContext, never()).enterPlatform();
        verify(orgContext, never()).clear();
        verify(chain).doFilter(request, response);
    }

    @Test
    void aFullyAuthenticatedSessionWithNoOrgMarkerBindsNothing() throws Exception {
        authenticate("bob", "ROLE_USER", Factors.MFA_COMPLETE);

        filter.doFilter(request, response, chain);

        verify(orgContext, never()).bindOrg(any());
        verify(orgContext, never()).enterPlatform();
        verify(orgContext, never()).clear();
    }

    @Test
    void aPreMfaSessionBindsNothingEvenForAnAdmin() throws Exception {
        // password step passed (carries ROLE_ADMIN) but MFA not complete -> no platform/tenant binding.
        authenticate("admin", Roles.ADMIN, Factors.ORG_PREFIX + UUID.randomUUID());

        filter.doFilter(request, response, chain);

        verify(orgContext, never()).enterPlatform();
        verify(orgContext, never()).bindOrg(any());
        verify(orgContext, never()).clear();
    }

    private void authenticate(String name, String... authorities) {
        var granted = Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(name, null, granted));
    }
}
