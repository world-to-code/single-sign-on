package com.example.sso.admin.internal.portalsettings.api;

import com.example.sso.admin.AdminPortalSettingsData;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;

/**
 * Admin request to update the portal security settings (elevation freshness, admin session lifetimes,
 * and the admin-console IP allowlist). Timeouts in minutes.
 */
public record AdminPortalSettingsRequest(
        @Min(1) @Max(1440) int reauthIntervalMinutes,
        @Min(1) @Max(1440) int elevationTokenTtlMinutes,
        @Min(1) @Max(1440) int sessionIdleTimeoutMinutes,
        @Min(1) @Max(10080) int sessionAbsoluteLifetimeMinutes,
        List<String> adminAllowedCidrs) {

    /** The settings update command (same shape as the public {@link AdminPortalSettingsData} projection). */
    public AdminPortalSettingsData toData() {
        return new AdminPortalSettingsData(reauthIntervalMinutes, elevationTokenTtlMinutes,
                sessionIdleTimeoutMinutes, sessionAbsoluteLifetimeMinutes, adminAllowedCidrs);
    }
}
