package com.example.sso.admin.internal.portalsettings.api;

import java.util.Optional;
import java.util.UUID;

/** Read model for the admin console's governing session policy (null = the acting admin's own policy). */
public record AdminPortalSettingsView(String sessionPolicyId) {

    static AdminPortalSettingsView of(Optional<UUID> policyId) {
        return new AdminPortalSettingsView(policyId.map(UUID::toString).orElse(null));
    }
}
