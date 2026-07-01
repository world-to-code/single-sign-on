package com.example.sso.admin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Update payload for the admin-portal security settings; all values are in minutes. */
public record AdminPortalSettingsRequest(
        @Min(1) @Max(1440) int reauthIntervalMinutes,
        @Min(1) @Max(1440) int elevationTokenTtlMinutes,
        @Min(1) @Max(1440) int sessionIdleTimeoutMinutes,
        @Min(1) @Max(10080) int sessionAbsoluteLifetimeMinutes) {
}
