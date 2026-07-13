package com.example.sso.admin.internal.portalsettings.api;

import com.example.sso.shared.error.BadRequestException;
import jakarta.validation.constraints.Min;
import java.util.UUID;

/**
 * Admin request configuring the admin console: WHICH session policy governs its step-up posture (a blank/absent
 * id clears the selection, falling back to the acting admin's own policy) PLUS the console-only enforcement
 * knobs — the elevation-token lifetime (platform-capped) and the entry IP allowlist (both in
 * {@code admin_console_config}).
 */
public record AdminConsoleSettingsRequest(
        String sessionPolicyId,
        @Min(1) int elevationTokenTtlMinutes,
        String adminAllowedCidrs) {

    /** The selected session policy id, or {@code null} to clear the selection. */
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
