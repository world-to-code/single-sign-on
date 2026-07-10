package com.example.sso.organization;

import java.util.UUID;

/**
 * Published when a new organization (tenant) is created, so other modules can provision that tenant's
 * baseline. Fired inside the creating transaction; delivery is MIXED by consumer: the tenant's own
 * baseline system roles + console entry are provisioned SYNCHRONOUSLY in the creating transaction (they
 * must exist for the org's first admin, created later in the same transaction — a failure rolls the whole
 * creation back), while the default session/auth policies are provisioned asynchronously AFTER the commit.
 */
public record OrganizationCreatedEvent(UUID orgId) {
}
