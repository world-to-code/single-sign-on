package com.example.sso.admin.internal.metrics.application;

import com.example.sso.audit.AuditSignInDay;
import java.util.List;
import java.util.UUID;

/** One tenant's analytics: identity, member count, and the daily sign-in trend over the window. */
public record OrgMetricsView(UUID id, String slug, String name, long users, int windowDays,
                             List<AuditSignInDay> signIns) {
}
