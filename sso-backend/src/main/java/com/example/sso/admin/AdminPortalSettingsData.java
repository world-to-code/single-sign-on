package com.example.sso.admin;

import java.util.UUID;

/**
 * Exposed, read-only projection of the admin-console settings: WHICH session policy governs the console for
 * this tenant ({@code null} = the policy resolved for the acting admin). Everything the console enforces —
 * idle/absolute lifetimes, step-up freshness, the elevation-token lifetime and the console IP allowlist —
 * lives on that session policy, so there is no parallel settings axis. This is the admin module's public
 * contract; the backing {@code AdminPortalSettings} entity stays module-internal.
 */
public record AdminPortalSettingsData(UUID sessionPolicyId) {
}
