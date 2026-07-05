package com.example.sso.admin.internal.metrics.application;

/** Cross-tenant totals for the platform super-admin dashboard. */
public record PlatformMetricsView(long organizations, long users, long signInsInWindow, int windowDays) {
}
