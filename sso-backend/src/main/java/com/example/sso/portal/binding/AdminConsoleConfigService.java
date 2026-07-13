package com.example.sso.portal.binding;

/**
 * The admin console's enforcement config (elevation-token lifetime + entry IP allowlist) for the acting tenant,
 * stored per-tenant with a GLOBAL default. Distinct from {@link PortalSessionBinding} (which selects WHICH
 * session policy governs the console): these are console-only knobs that used to live on every session policy.
 * A tenant reads its own config, else the GLOBAL default it inherits; only the platform context may edit the
 * global default. Resolution is scoped to the acting org so an un-drilled super-admin sees the global default.
 */
public interface AdminConsoleConfigService {

    /** The console config governing the acting tenant: its own row, else the inherited GLOBAL default. */
    AdminConsoleConfigView current();

    /** Sets the acting tenant's console config (platform context edits the GLOBAL default). An invalid CIDR is
     *  rejected; a blank allowlist clears it (any network). */
    void update(int elevationTokenTtlMinutes, String adminAllowedCidrs);
}
