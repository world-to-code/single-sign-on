package com.example.sso.admin.internal.portalsettings.api;

import com.example.sso.admin.AdminPortalSettingsData;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;

/**
 * Admin request to update the admin-console-specific security settings: the elevation-token TTL and the
 * admin-console IP allowlist. The admin session lifetimes and step-up freshness are managed by the session
 * policy assigned to the admin, not here. TTL in minutes.
 */
public record AdminPortalSettingsRequest(
        @Min(1) @Max(1440) int elevationTokenTtlMinutes,
        List<String> adminAllowedCidrs) {

    /** The settings update command (same shape as the public {@link AdminPortalSettingsData} projection). */
    public AdminPortalSettingsData toData() {
        return new AdminPortalSettingsData(elevationTokenTtlMinutes, adminAllowedCidrs);
    }
}
