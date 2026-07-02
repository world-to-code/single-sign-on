package com.example.sso.admin.internal.portalsettings.application;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.List;

/** Update payload for the admin-portal security settings; timeouts are in minutes. */
public record AdminPortalSettingsRequest(
        @Min(1) @Max(1440) int reauthIntervalMinutes,
        @Min(1) @Max(1440) int elevationTokenTtlMinutes,
        @Min(1) @Max(1440) int sessionIdleTimeoutMinutes,
        @Min(1) @Max(10080) int sessionAbsoluteLifetimeMinutes,
        List<String> adminAllowedCidrs) {
}
