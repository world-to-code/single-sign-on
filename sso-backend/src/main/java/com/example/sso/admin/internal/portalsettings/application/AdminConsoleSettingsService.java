package com.example.sso.admin.internal.portalsettings.application;

import com.example.sso.portal.binding.AdminConsoleConfigService;
import com.example.sso.portal.binding.PortalApps;
import com.example.sso.portal.binding.PortalSessionBinding;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies the admin console's settings — its governing session policy (step-up posture) AND its console-only
 * enforcement config (elevation-token lifetime + entry IP allowlist) — in ONE transaction. A validation failure
 * on either half (e.g. a malformed CIDR) therefore rolls back both, so the console can never be left with a new
 * session policy but a stale/absent allowlist (a half-applied, fail-open network control).
 */
@Service
@RequiredArgsConstructor
public class AdminConsoleSettingsService {

    private final PortalSessionBinding portalBinding;
    private final AdminConsoleConfigService consoleConfig;

    @Transactional
    public void update(UUID sessionPolicyId, int elevationTokenTtlMinutes, String adminAllowedCidrs) {
        portalBinding.setSessionPolicy(PortalApps.ADMIN, sessionPolicyId);
        consoleConfig.update(elevationTokenTtlMinutes, adminAllowedCidrs);
    }
}
