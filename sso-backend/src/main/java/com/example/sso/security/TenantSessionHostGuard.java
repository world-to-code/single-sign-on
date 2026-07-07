package com.example.sso.security;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.shared.web.ClientIp;
import com.example.sso.tenancy.OrgContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Zero-Trust tenant↔session binding on the app chain: an authenticated session is bound to the tenant
 * (organization) it was established for and MUST NOT be honoured on a DIFFERENT tenant's subdomain host. Runs
 * AFTER {@link OrgContextFilter}, which has already bound the session's org (its {@code ORG_} marker) or the
 * platform context. When the session carries a specific org but the request host addresses a DIFFERENT active
 * tenant — or an unknown/suspended one — the request is refused, so a session leaked onto another tenant's host
 * (a misconfigured cookie domain, a caching proxy, DNS rebinding) cannot read that tenant's data. This is
 * defence in depth ON TOP OF host-only cookie scoping (the {@code SESSION} cookie has no Domain, so it is not
 * sent cross-host), not a replacement for it.
 *
 * <p>Deliberately permissive where a host pin does not apply: the platform (super-admin) context — a super-admin
 * operates cross-org and drills in via {@link OrgDrillInFilter}; the bare apex/base domain — home of tenant-first
 * login and the SPA; and any request with no org bound (pre-MFA / anonymous). A mismatch does NOT invalidate the
 * session (it stays valid on its own host) — only the mismatched request is refused, so a transient resolution
 * hiccup can never log a user out of their legitimate tenant. Instantiated (not a {@code @Component}) so it runs
 * only on the app chain it is added to.
 */
public class TenantSessionHostGuard extends OncePerRequestFilter {

    private final HostOrgResolver hostOrgResolver;
    private final OrgContext orgContext;
    private final AuditService audit;

    public TenantSessionHostGuard(HostOrgResolver hostOrgResolver, OrgContext orgContext, AuditService audit) {
        this.hostOrgResolver = hostOrgResolver;
        this.orgContext = orgContext;
        this.audit = audit;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Platform (super-admin) is exempt — it operates cross-org and drills in via X-Org-Context.
        if (orgContext.isPlatform()) {
            chain.doFilter(request, response);
            return;
        }
        Optional<UUID> sessionOrg = orgContext.currentOrg();
        Optional<UUID> sessionCustomer = orgContext.currentCustomer();
        // Unbound (pre-MFA / anonymous): no single tenant to protect.
        if (sessionOrg.isEmpty() && sessionCustomer.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        String host = request.getServerName();
        if (hostOrgResolver.isBaseDomain(host)) {
            chain.doFilter(request, response); // the apex hosts tenant-first login + the SPA — no host pin
            return;
        }
        // A customer-console session may operate on ANY host of its OWN customer (the console and its orgs); an
        // org-bound session only on its own org's host. Anything else — another tenant's host, or a host that is
        // not an active tenant — is refused.
        boolean matches = sessionCustomer.isPresent()
                ? hostOrgResolver.resolveHostCustomer(host).filter(sessionCustomer.get()::equals).isPresent()
                : hostOrgResolver.resolveOrg(host).filter(sessionOrg.get()::equals).isPresent();
        if (matches) {
            chain.doFilter(request, response);
            return;
        }
        // Refuse the request WITHOUT touching the session (it remains valid on its own host).
        String bound = sessionCustomer.map(c -> "customer=" + c).orElseGet(() -> "org=" + sessionOrg.get());
        audit.record(new AuditRecord(AuditType.SESSION_CONTEXT_MISMATCH, principal(), false,
                "session " + bound + " used on host=" + host, ClientIp.of(request)));
        response.sendError(HttpStatus.UNAUTHORIZED.value());
    }

    private String principal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : auth.getName();
    }
}
