package com.example.sso.organization;

import java.util.UUID;

/**
 * Published when a user's access to an organization is revoked — either their membership was removed or
 * the org was suspended (fanned out per member). NOT a general membership-delta fact: it is never fired on
 * {@code addMember}. The session module reacts by ending that user's live sessions bound to the org
 * (tenant-aware session control).
 */
public record OrganizationAccessRevokedEvent(UUID orgId, UUID userId) {
}
