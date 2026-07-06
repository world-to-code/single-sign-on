package com.example.sso.security;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
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
 * The Zero-Trust tenant↔session guard: a session bound to org A may be used on org A's host or the apex, but a
 * request that presents it on a DIFFERENT tenant's host (or an unknown/suspended one) is refused with 401 while
 * the session itself is left intact. Platform (super-admin) and unbound (pre-MFA) sessions are exempt.
 */
@ExtendWith(MockitoExtension.class)
class TenantSessionHostGuardTest {

    @Mock private HostOrgResolver hostOrgResolver;
    @Mock private OrgContext orgContext;
    @Mock private AuditService audit;

    @InjectMocks private TenantSessionHostGuard guard;

    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    @Mock private FilterChain chain;

    @Test
    void aPlatformSessionIsExemptAndNeverTouchesTheHost() throws Exception {
        when(orgContext.currentOrg()).thenReturn(Optional.of(UUID.randomUUID()));
        when(orgContext.isPlatform()).thenReturn(true);
        request.setServerName("seoul.acme.localhost");

        guard.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(hostOrgResolver, never()).resolveOrg(any());
        verify(audit, never()).record(any());
    }

    @Test
    void anUnboundSessionIsExempt() throws Exception {
        when(orgContext.currentOrg()).thenReturn(Optional.empty()); // pre-MFA / anonymous — no tenant to pin
        request.setServerName("seoul.acme.localhost");

        guard.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(hostOrgResolver, never()).resolveOrg(any());
    }

    @Test
    void theApexIsExemptSoTenantFirstLoginKeepsWorking() throws Exception {
        when(orgContext.currentOrg()).thenReturn(Optional.of(UUID.randomUUID()));
        when(orgContext.isPlatform()).thenReturn(false);
        request.setServerName("localhost");
        when(hostOrgResolver.isBaseDomain("localhost")).thenReturn(true);

        guard.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(hostOrgResolver, never()).resolveOrg(any());
    }

    @Test
    void aSessionOnItsOwnTenantHostPasses() throws Exception {
        UUID orgId = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(orgId));
        when(orgContext.isPlatform()).thenReturn(false);
        request.setServerName("main.acme.localhost");
        when(hostOrgResolver.isBaseDomain("main.acme.localhost")).thenReturn(false);
        when(hostOrgResolver.resolveOrg("main.acme.localhost")).thenReturn(Optional.of(orgId));

        guard.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(audit, never()).record(any());
    }

    @Test
    void aSessionPresentedOnADifferentTenantHostIsRefused() throws Exception {
        UUID sessionOrg = UUID.randomUUID();
        UUID otherOrg = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(sessionOrg));
        when(orgContext.isPlatform()).thenReturn(false);
        request.setServerName("main.other.localhost");
        when(hostOrgResolver.isBaseDomain("main.other.localhost")).thenReturn(false);
        when(hostOrgResolver.resolveOrg("main.other.localhost")).thenReturn(Optional.of(otherOrg));

        guard.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
        verify(audit).record(any(AuditRecord.class)); // the mismatch is audited
    }

    @Test
    void aSessionOnAnUnknownOrSuspendedTenantHostIsRefused() throws Exception {
        UUID sessionOrg = UUID.randomUUID();
        when(orgContext.currentOrg()).thenReturn(Optional.of(sessionOrg));
        when(orgContext.isPlatform()).thenReturn(false);
        request.setServerName("gone.localhost");
        when(hostOrgResolver.isBaseDomain("gone.localhost")).thenReturn(false);
        when(hostOrgResolver.resolveOrg("gone.localhost")).thenReturn(Optional.empty());

        guard.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(any(), any());
    }
}
