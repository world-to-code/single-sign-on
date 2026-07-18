package com.example.sso.branding.internal.api;

import com.example.sso.branding.Branding;
import com.example.sso.branding.internal.application.BrandingService;
import com.example.sso.security.HostOrgResolver;
import com.example.sso.tenancy.OrgContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PUBLIC (unauthenticated) branding for the login/MFA SPA: the tenant is selected by the REQUEST HOST (a client
 * can only ever read its own subdomain's branding), and branding is public data anyway. The org is resolved
 * from the host and the read runs inside that org's context so RLS surfaces the tenant's own row (else a
 * bare/unknown host falls back to the platform/built-in default). {@code /api/auth/**} is already permit-all.
 */
@RestController
@RequestMapping("/api/auth/branding")
@RequiredArgsConstructor
public class BrandingController {

    private final BrandingService service;
    private final HostOrgResolver hostOrgResolver;
    private final OrgContext orgContext;

    @GetMapping
    public Branding get(HttpServletRequest request) {
        UUID orgId = hostOrgResolver.resolveOrg(request.getServerName()).orElse(null);
        return orgContext.callInOrg(orgId, () -> service.resolve(orgId));
    }
}
