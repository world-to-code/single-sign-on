package com.example.sso.admin.internal.metrics.api;

import com.example.sso.admin.internal.metrics.application.MetricsAdminService;
import com.example.sso.admin.internal.metrics.application.OrgMetricsView;
import com.example.sso.admin.internal.metrics.application.PlatformMetricsView;
import com.example.sso.admin.internal.shared.security.CanViewOrg;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only analytics API for the console dashboards. */
@RestController
@RequestMapping("/api/admin/metrics")
@RequiredArgsConstructor
public class AdminMetricsController {

    private final MetricsAdminService metrics;

    /** Cross-tenant totals — platform super-admin only (a tenant admin has no cross-tenant view). */
    @GetMapping("/platform")
    @PreAuthorize("@adminAccessPolicy.isCurrentActorUnscoped()")
    public PlatformMetricsView platform() {
        return metrics.platform();
    }

    /** One tenant's analytics — a super admin may read any org, a scoped org-admin only their own. */
    @GetMapping("/orgs/{id}")
    @CanViewOrg
    public OrgMetricsView organization(@PathVariable UUID id) {
        return metrics.organization(id);
    }
}
