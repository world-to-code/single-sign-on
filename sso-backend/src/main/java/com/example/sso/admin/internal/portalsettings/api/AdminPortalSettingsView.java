package com.example.sso.admin.internal.portalsettings.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Read model for the user portal's governing session policy. {@code sessionPolicyId} is the tenant's OWN
 * selection only — null means it is inheriting, and {@code inheritedSessionPolicyName} says what from. Kept
 * apart for the same reason as the console view: an inherited GLOBAL id is not a value this caller may select
 * or save back.
 */
public record AdminPortalSettingsView(String sessionPolicyId, String sessionPolicyName,
                                      String inheritedSessionPolicyName) {

    static AdminPortalSettingsView of(Optional<UUID> policyId, String sessionPolicyName,
            String inheritedSessionPolicyName) {
        return new AdminPortalSettingsView(policyId.map(UUID::toString).orElse(null), sessionPolicyName,
                inheritedSessionPolicyName);
    }
}
