package com.example.sso.admin.internal.portalsettings.api;

import com.example.sso.admin.AdminPortalSettingsData;

import java.util.List;

/** Read model for the admin-console-specific security settings (elevation-token TTL + IP allowlist). */
public record AdminPortalSettingsView(int elevationTokenTtlMinutes, List<String> adminAllowedCidrs) {

    static AdminPortalSettingsView of(AdminPortalSettingsData settings) {
        return new AdminPortalSettingsView(settings.elevationTokenTtlMinutes(), settings.adminAllowedCidrs());
    }
}
