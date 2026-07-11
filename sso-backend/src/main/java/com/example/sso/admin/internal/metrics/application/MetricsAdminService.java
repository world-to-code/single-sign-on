package com.example.sso.admin.internal.metrics.application;

import com.example.sso.audit.AuditService;
import com.example.sso.organization.OrganizationService;
import com.example.sso.organization.OrganizationView;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.user.account.UserService;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only analytics for the console: cross-tenant totals for the platform super-admin, and a per-tenant
 * view (member count + sign-in trend). Composes the organization, user and audit modules; carries no state
 * of its own. Authorization (platform-only vs own-org) is enforced at the controller.
 */
@Service
@RequiredArgsConstructor
public class MetricsAdminService {

    private final OrganizationService organizations;
    private final UserService users;
    private final AuditService audit;

    @Value("${sso.metrics.trend-days}")
    private int trendDays;

    @Transactional(readOnly = true)
    public PlatformMetricsView platform() {
        return new PlatformMetricsView(organizations.listAll().size(), users.count(),
                audit.signInsSince(windowStart()), trendDays);
    }

    @Transactional(readOnly = true)
    public OrgMetricsView organization(UUID id) {
        OrganizationView org = organizations.findView(id)
                .orElseThrow(() -> new NotFoundException("organization not found"));
        return new OrgMetricsView(org.id(), org.slug(), org.name(),
                organizations.memberCount(id), trendDays, audit.signInTrend(id, windowStart()));
    }

    private Instant windowStart() {
        return Instant.now().minus(Duration.ofDays(trendDays));
    }
}
