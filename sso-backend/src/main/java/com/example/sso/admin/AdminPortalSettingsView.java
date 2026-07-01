package com.example.sso.admin;

/** Read model for the admin-portal security settings. */
public record AdminPortalSettingsView(int reauthIntervalMinutes, int elevationTokenTtlMinutes,
                                      int sessionIdleTimeoutMinutes, int sessionAbsoluteLifetimeMinutes) {

    static AdminPortalSettingsView of(AdminPortalSettings settings) {
        return new AdminPortalSettingsView(
                settings.getReauthIntervalMinutes(), settings.getElevationTokenTtlMinutes(),
                settings.getSessionIdleTimeoutMinutes(), settings.getSessionAbsoluteLifetimeMinutes());
    }
}
