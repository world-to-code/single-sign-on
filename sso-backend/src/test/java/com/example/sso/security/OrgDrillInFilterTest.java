package com.example.sso.security;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.organization.OrganizationService;
import com.example.sso.organization.OrganizationView;
import com.example.sso.organization.OrganizationStatus;
import com.example.sso.tenancy.OrgContext;
import jakarta.servlet.FilterChain;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OrgDrillInFilter}: a platform super-admin ({@code isPlatform()}) may scope an
 * admin request to a valid tenant via {@code X-Org-Context}; a non-platform caller (tenant admin /
 * unauthenticated) is refused 403; malformed / unknown orgs are rejected; and any request without the
 * header — or off the admin path — passes through untouched.
 */
class OrgDrillInFilterTest {

    private final OrgContext orgContext = mock(OrgContext.class);
    private final OrganizationService organizations = mock(OrganizationService.class);
    private final AuditService audit = mock(AuditService.class);
    private final OrgDrillInFilter filter = new OrgDrillInFilter(orgContext, organizations, audit);
    private final FilterChain chain = mock(FilterChain.class);

    private MockHttpServletRequest adminRequest(String orgHeader) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/users");
        request.setRequestURI("/api/admin/users");
        if (orgHeader != null) {
            request.addHeader(OrgDrillInFilter.HEADER, orgHeader);
        }
        return request;
    }

    @Test
    void noHeaderPassesThroughWithoutBinding() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(adminRequest(null), response, chain);

        verify(chain).doFilter(any(), any());
        verify(orgContext, never()).bindOrg(any());
    }

    @Test
    void aHeaderOnANonAdminPathIsIgnored() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/me");
        request.setRequestURI("/api/me");
        request.addHeader(OrgDrillInFilter.HEADER, UUID.randomUUID().toString());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(any(), any());
        verify(orgContext, never()).bindOrg(any());
    }

    @Test
    void aSuperAdminDrillsIntoAValidOrg() throws Exception {
        UUID orgId = UUID.randomUUID();
        when(orgContext.isPlatform()).thenReturn(true);
        when(organizations.findView(orgId)).thenReturn(Optional.of(
                new OrganizationView(orgId, "acme", "Acme", OrganizationStatus.ACTIVE, Instant.now())));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(adminRequest(orgId.toString()), response, chain);

        verify(orgContext).bindOrg(orgId); // RLS now scoped to the tenant
        verify(chain).doFilter(any(), any());
        ArgumentCaptor<AuditRecord> rec = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit).record(rec.capture());
        assertThat(rec.getValue().type()).isEqualTo(AuditType.ORGANIZATION_CONTEXT_ENTERED);
    }

    @Test
    void aNonPlatformCallerIsRefusedAndAudited() throws Exception {
        UUID orgId = UUID.randomUUID();
        when(orgContext.isPlatform()).thenReturn(false); // a tenant admin (org-bound) or unauthenticated
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(adminRequest(orgId.toString()), response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        verify(orgContext, never()).bindOrg(any());
        verify(chain, never()).doFilter(any(), any());
        ArgumentCaptor<AuditRecord> rec = ArgumentCaptor.forClass(AuditRecord.class);
        verify(audit).record(rec.capture());
        assertThat(rec.getValue().type()).isEqualTo(AuditType.AUTHORIZATION_DENIED);
    }

    @Test
    void aMalformedOrgHeaderIsRejected() throws Exception {
        when(orgContext.isPlatform()).thenReturn(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(adminRequest("not-a-uuid"), response, chain);

        assertThat(response.getStatus()).isEqualTo(400);
        verify(orgContext, never()).bindOrg(any());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void anUnknownOrgIsRejected() throws Exception {
        UUID orgId = UUID.randomUUID();
        when(orgContext.isPlatform()).thenReturn(true);
        when(organizations.findView(orgId)).thenReturn(Optional.empty());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(adminRequest(orgId.toString()), response, chain);

        assertThat(response.getStatus()).isEqualTo(404);
        verify(orgContext, never()).bindOrg(any());
        verify(chain, never()).doFilter(any(), any());
    }
}
