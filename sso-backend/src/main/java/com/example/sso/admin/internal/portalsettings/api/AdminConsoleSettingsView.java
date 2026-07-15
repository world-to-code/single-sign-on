package com.example.sso.admin.internal.portalsettings.api;

import com.example.sso.portal.binding.AdminConsoleConfigView;
import java.util.Optional;
import java.util.UUID;

/**
 * Read model for the admin console's settings: the session policy governing its step-up posture (null = the
 * acting admin's own policy) and the console-only enforcement knobs (elevation-token lifetime, entry IP
 * allowlist). The policy NAME is resolved server-side because the bound policy may be an inherited GLOBAL
 * default not present in a tenant admin's own-tier policy list — the client would otherwise show only the id.
 */
public record AdminConsoleSettingsView(String sessionPolicyId, String sessionPolicyName,
                                       int elevationTokenTtlMinutes, String adminAllowedCidrs) {

    static AdminConsoleSettingsView of(Optional<UUID> policyId, String sessionPolicyName, AdminConsoleConfigView config) {
        return new AdminConsoleSettingsView(policyId.map(UUID::toString).orElse(null), sessionPolicyName,
                config.elevationTokenTtlMinutes(), config.adminAllowedCidrs());
    }
}
