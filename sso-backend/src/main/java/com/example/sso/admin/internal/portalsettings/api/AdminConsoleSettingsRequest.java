package com.example.sso.admin.internal.portalsettings.api;

import com.example.sso.shared.error.BadRequestException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;

/**
 * Admin request configuring the admin console: WHICH session policy governs it (a blank/absent id clears the
 * selection, falling back to the acting admin's own policy) PLUS the console-only enforcement knobs — the
 * elevation-token lifetime and the entry IP allowlist. The latter two live in {@code admin_console_config},
 * not on a session policy.
 */
public record AdminConsoleSettingsRequest(
        String sessionPolicyId,
        @Min(1) @Max(1440) int elevationTokenTtlMinutes,
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
