package com.example.sso.admin.internal.portalsettings.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Read model for the user portal's governing session policy (null = the acting user's own resolved policy). The
 * NAME is resolved server-side because the bound policy may be an inherited GLOBAL default that a tenant admin's
 * own-tier policy list does not include — the client would otherwise have only the id to show.
 */
public record AdminPortalSettingsView(String sessionPolicyId, String sessionPolicyName) {

    static AdminPortalSettingsView of(Optional<UUID> policyId, String sessionPolicyName) {
        return new AdminPortalSettingsView(policyId.map(UUID::toString).orElse(null), sessionPolicyName);
    }
}
