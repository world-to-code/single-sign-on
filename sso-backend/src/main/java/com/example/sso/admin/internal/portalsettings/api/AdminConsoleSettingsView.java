package com.example.sso.admin.internal.portalsettings.api;

import com.example.sso.portal.binding.AdminConsoleConfigView;
import java.util.Optional;
import java.util.UUID;

/**
 * Read model for the admin console's settings: its governing session policy (null = the acting admin's own
 * policy) and the console-only enforcement knobs (elevation-token lifetime, entry IP allowlist).
 */
public record AdminConsoleSettingsView(String sessionPolicyId, int elevationTokenTtlMinutes,
                                       String adminAllowedCidrs) {

    static AdminConsoleSettingsView of(Optional<UUID> policyId, AdminConsoleConfigView config) {
        return new AdminConsoleSettingsView(policyId.map(UUID::toString).orElse(null),
                config.elevationTokenTtlMinutes(), config.adminAllowedCidrs());
    }
}
