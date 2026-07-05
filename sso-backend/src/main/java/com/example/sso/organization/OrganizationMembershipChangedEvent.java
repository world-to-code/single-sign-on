package com.example.sso.organization;

import java.util.UUID;

/**
 * Published when a user's membership in an organization is revoked (or the org is suspended), so the
 * session module can end that user's live sessions bound to the org (tenant-aware session control).
 */
public record OrganizationMembershipChangedEvent(UUID orgId, UUID userId) {
}
