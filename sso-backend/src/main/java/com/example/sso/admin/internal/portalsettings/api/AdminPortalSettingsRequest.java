package com.example.sso.admin.internal.portalsettings.api;

import com.example.sso.admin.AdminPortalSettingsData;
import com.example.sso.shared.error.BadRequestException;
import java.util.UUID;

/**
 * Admin request selecting the session policy that governs the admin console. A blank/absent id clears the
 * selection, so the console falls back to the policy resolved for the acting admin. Everything the console
 * enforces (step-up freshness, elevation-token lifetime, IP allowlist) lives on the chosen policy.
 */
public record AdminPortalSettingsRequest(String sessionPolicyId) {

    /** The settings update command (same shape as the public {@link AdminPortalSettingsData} projection). */
    public AdminPortalSettingsData toData() {
        if (sessionPolicyId == null || sessionPolicyId.isBlank()) {
            return new AdminPortalSettingsData(null);
        }
        try {
            return new AdminPortalSettingsData(UUID.fromString(sessionPolicyId));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("invalid session policy id");
        }
    }
}
