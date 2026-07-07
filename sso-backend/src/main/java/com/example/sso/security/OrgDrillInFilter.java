package com.example.sso.security;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.organization.OrganizationAuthorization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.shared.web.ClientIp;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Lets an admin "drill into" one tenant: when an admin-API request carries an {@code X-Org-Context: <orgId>}
 * header, the request runs bound to that org so RLS scopes every org-scoped read/write to that tenant. Runs
 * AFTER {@link OrgContextFilter}, which has already bound the base context; this overrides it for the target
 * org. The outer filter clears the context at request end, so no restore is needed here.
 *
 * <p>Zero-trust, deny-by-default: a caller may drill into an org ONLY if they are a platform super-admin
 * ({@code OrgContext.isPlatform()}) OR they administer that org — an org-admin AND a member of it
 * ({@link OrganizationAuthorization#canManage}). A tenant admin cannot switch into an org they do not
 * administer (a bare role with no membership is bounded to nothing). Membership is re-checked LIVE
 * here (not trusted from a frozen session). Instantiated (not a {@code @Component}); only acts on
 * {@code /api/admin/**}.
 */
public class OrgDrillInFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Org-Context";
    private static final String ADMIN_PATH = "/api/admin";

    private final OrgContext orgContext;
    private final OrganizationService organizations;
    private final OrganizationAuthorization orgAuthorization;
    private final UserService users;
    private final AuditService audit;

    public OrgDrillInFilter(OrgContext orgContext, OrganizationService organizations,
                            OrganizationAuthorization orgAuthorization, UserService users, AuditService audit) {
        this.orgContext = orgContext;
        this.organizations = organizations;
        this.orgAuthorization = orgAuthorization;
        this.users = users;
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

        UUID orgId;
        try {
            orgId = UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            deny(request, response, "malformed org", raw, HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        // Deny-by-default: only a platform super-admin, or someone who administers this org (org-admin member
        // or customer-admin of its customer), may switch into it. canManage re-checks membership live.
        if (!orgContext.isPlatform() && !mayDrillInto(orgId)) {
            deny(request, response, "not authorized for org", orgId.toString(), HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        if (organizations.findView(orgId).isEmpty()) {
            deny(request, response, "unknown org", orgId.toString(), HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        orgContext.bindOrg(orgId); // override the base context → scope RLS to this tenant for the request
        audit.record(new AuditRecord(AuditType.ORGANIZATION_CONTEXT_ENTERED, principal(), true,
                "org=" + orgId, ClientIp.of(request)));
        chain.doFilter(request, response);
    }

    private boolean mayDrillInto(UUID orgId) {
        return currentUserId().map(userId -> orgAuthorization.canManage(userId, orgId)).orElse(false);
    }

    private Optional<UUID> currentUserId() {
        String username = principal();
        return username == null ? Optional.empty() : users.findByLogin(username).map(UserAccount::getId);
    }

    private void deny(HttpServletRequest request, HttpServletResponse response, String reason, String org, int status)
            throws IOException {
        audit.record(new AuditRecord(AuditType.AUTHORIZATION_DENIED, principal(), false,
                "org drill-in refused (" + reason + ") org=" + org, ClientIp.of(request)));
        response.sendError(status);
    }

    private String principal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : auth.getName();
    }
}
