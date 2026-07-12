package com.example.sso.admin.internal.portalsettings.api;

import com.example.sso.shared.error.BadRequestException;
import java.util.UUID;

/**
 * Admin request selecting the session policy that governs the admin console. A blank/absent id clears the
 * selection, so the console falls back to the policy resolved for the acting admin. Everything the console
 * enforces (step-up freshness, elevation-token lifetime, IP allowlist) lives on the chosen policy.
 */
public record AdminPortalSettingsRequest(String sessionPolicyId) {

    /** The selected policy id, or {@code null} to clear the selection. */
    public UUID toPolicyId() {
        if (sessionPolicyId == null || sessionPolicyId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(sessionPolicyId);
        } catch (IllegalArgumentException e) {
            throw BadRequestException.of("admin.sessionPolicy.invalidId");
        }
    }
}
