package com.example.sso.admin;

import java.time.Duration;

/**
 * Exposed, read-only projection of the admin-portal security settings (the {@code admin-console}
 * elevation path knobs). This is the admin module's public contract; the backing
 * {@code AdminPortalSettings} entity stays module-internal.
 */
public record AdminPortalSettingsData(int reauthIntervalMinutes, int elevationTokenTtlMinutes,
                                      int sessionIdleTimeoutMinutes, int sessionAbsoluteLifetimeMinutes) {

    public Duration reauthInterval() {
        return Duration.ofMinutes(reauthIntervalMinutes);
    }

    public Duration sessionIdleTimeout() {
        return Duration.ofMinutes(sessionIdleTimeoutMinutes);
    }

    public Duration sessionAbsoluteLifetime() {
        return Duration.ofMinutes(sessionAbsoluteLifetimeMinutes);
    }
}
