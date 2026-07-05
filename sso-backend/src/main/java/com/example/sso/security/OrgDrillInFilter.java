package com.example.sso.security;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.organization.OrganizationService;
import com.example.sso.shared.web.ClientIp;
import com.example.sso.tenancy.OrgContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Lets a platform super-admin "drill into" one tenant: when an admin-API request carries an
 * {@code X-Org-Context: <orgId>} header, the request runs bound to that org so RLS scopes every
 * org-scoped read/write to that tenant (the super-admin keeps their full permissions — only the DATA is
 * scoped). Runs AFTER {@link OrgContextFilter}, which has already bound the platform context for a
 * super-admin; this overrides it for the target org. The outer filter clears the context at request end,
 * so no restore is needed here.
 *
 * <p>Zero-trust: a drill-in is a super-admin operation ONLY. {@code OrgContext.isPlatform()} is true iff
 * the caller is a fully-authenticated super-admin (else they are org-bound or unauthenticated), so a
 * tenant admin (or anyone) sending the header is refused with 403 — no caller may switch into an org they
 * are not a platform admin of. Instantiated (not a {@code @Component}) so it runs only on the app chain it
 * is added to. Only acts on {@code /api/admin/**}; every other request passes through untouched.
 */
public class OrgDrillInFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Org-Context";
    private static final String ADMIN_PATH = "/api/admin";

    private final OrgContext orgContext;
    private final OrganizationService organizations;
    private final AuditService audit;

    public OrgDrillInFilter(OrgContext orgContext, OrganizationService organizations, AuditService audit) {
        this.orgContext = orgContext;
        this.organizations = organizations;
        this.audit = audit;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String raw = request.getHeader(HEADER);
        if (raw == null || raw.isBlank() || !request.getRequestURI().startsWith(ADMIN_PATH)) {
            chain.doFilter(request, response);
            return;
        }

        // A drill-in is a platform super-admin operation only. isPlatform() is true iff a fully-authenticated
        // super-admin; a tenant admin is org-bound, so this refuses any attempt to switch tenants.
        if (!orgContext.isPlatform()) {
            audit.record(new AuditRecord(AuditType.AUTHORIZATION_DENIED, principal(), false,
                    "org drill-in refused (not a platform admin) org=" + raw, ClientIp.of(request)));
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        UUID orgId;
        try {
            orgId = UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            audit.record(new AuditRecord(AuditType.AUTHORIZATION_DENIED, principal(), false,
                    "org drill-in refused (malformed org) org=" + raw, ClientIp.of(request)));
            response.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        if (organizations.findView(orgId).isEmpty()) {
            audit.record(new AuditRecord(AuditType.AUTHORIZATION_DENIED, principal(), false,
                    "org drill-in refused (unknown org) org=" + orgId, ClientIp.of(request)));
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        orgContext.bindOrg(orgId); // override platform → scope RLS to this tenant for the request
        audit.record(new AuditRecord(AuditType.ORGANIZATION_CONTEXT_ENTERED, principal(), true,
                "org=" + orgId, ClientIp.of(request)));
        chain.doFilter(request, response);
    }

    private String principal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : auth.getName();
    }
}
