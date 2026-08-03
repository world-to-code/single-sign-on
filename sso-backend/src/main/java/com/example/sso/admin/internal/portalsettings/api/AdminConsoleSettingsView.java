package com.example.sso.admin.internal.portalsettings.api;

import com.example.sso.portal.binding.AdminConsoleConfigView;
import java.util.Optional;
import java.util.UUID;

/**
 * Read model for the admin console's settings: the session policy governing its step-up posture and the
 * console-only enforcement knobs (elevation-token lifetime, entry IP allowlist).
 *
 * <p>{@code effectiveSensitiveReauthWindowMinutes} is display-only, and it is the number that surprises
 * people: the elevation TTL keeps the console UNLOCKED, but every {@code @RequireStepUp} action is gated by
 * this window from the GOVERNING session policy. Setting a long elevation TTL beside an inherited 2-minute
 * window looked like the TTL was being ignored, so the screen now states both.
 *
 * <p>{@code sessionPolicyId} is the tenant's OWN selection only — null means it is inheriting, and
 * {@code inheritedSessionPolicyName} then says what from. The two are kept apart because echoing an inherited
 * GLOBAL id back as though it were a selection is refused by the write guard, which used to fail the whole
 * form (including the elevation TTL) for an administrator who had merely never chosen a policy.
 */
public record AdminConsoleSettingsView(String sessionPolicyId, String sessionPolicyName,
                                       String inheritedSessionPolicyName,
                                       int elevationTokenTtlMinutes, String adminAllowedCidrs,
                                       Integer effectiveSensitiveReauthWindowMinutes) {

    static AdminConsoleSettingsView of(Optional<UUID> policyId, String sessionPolicyName,
            String inheritedSessionPolicyName, AdminConsoleConfigView config, Integer sensitiveWindowMinutes) {
        return new AdminConsoleSettingsView(policyId.map(UUID::toString).orElse(null), sessionPolicyName,
                inheritedSessionPolicyName, config.elevationTokenTtlMinutes(), config.adminAllowedCidrs(),
                sensitiveWindowMinutes);
    }
}
