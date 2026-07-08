package com.example.sso.organization;

import java.util.UUID;

/**
 * Published when a new organization (tenant) is created, so other modules can provision that tenant's
 * baseline. Fired inside the creating transaction and delivered AFTER it commits, so the org row exists
 * before any listener runs. Consumed to seed the tenant's own default session policy and auth policy.
 */
public record OrganizationCreatedEvent(UUID orgId) {
}
