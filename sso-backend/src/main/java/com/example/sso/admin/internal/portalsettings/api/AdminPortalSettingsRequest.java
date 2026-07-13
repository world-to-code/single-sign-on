package com.example.sso.admin.internal.portalsettings.api;

import com.example.sso.shared.error.BadRequestException;
import java.util.UUID;

/**
 * Admin request selecting the session policy that governs the END-USER PORTAL (the {@code /user} endpoint). A
 * blank/absent id clears the selection, so the portal falls back to each user's own resolved policy. Only the
 * general session posture (lifetimes, reauth) applies here — the admin console's elevation TTL and IP allowlist
 * are console-only and live in {@code admin_console_config}, not on this policy.
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
