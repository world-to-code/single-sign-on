package com.example.sso.admin.internal.api;

import com.example.sso.admin.AdminPortalSettingsData;

import java.util.List;

/** Read model for the admin-portal security settings. */
public record AdminPortalSettingsView(int reauthIntervalMinutes, int elevationTokenTtlMinutes,
                                      int sessionIdleTimeoutMinutes, int sessionAbsoluteLifetimeMinutes,
                                      List<String> adminAllowedCidrs) {

    static AdminPortalSettingsView of(AdminPortalSettingsData settings) {
        return new AdminPortalSettingsView(
                settings.reauthIntervalMinutes(), settings.elevationTokenTtlMinutes(),
                settings.sessionIdleTimeoutMinutes(), settings.sessionAbsoluteLifetimeMinutes(),
                settings.adminAllowedCidrs());
    }
}
