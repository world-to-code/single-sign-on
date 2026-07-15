package com.example.sso.admin.internal.portalsettings.api;

import com.example.sso.admin.internal.portalsettings.application.AdminConsoleSettingsService;
import com.example.sso.portal.binding.AdminConsoleConfigService;
import com.example.sso.portal.binding.PortalApps;
import com.example.sso.portal.binding.PortalSessionBinding;
import com.example.sso.session.policy.SessionPolicyDetails;
import com.example.sso.session.policy.SessionPolicyService;
import com.example.sso.shared.security.RequirePermission;
import com.example.sso.user.rbac.Permissions;
import jakarta.validation.Valid;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin API for portal settings. The root path configures the ADMIN CONSOLE — the session policy governing its
 * step-up posture (a {@code PORTAL}/{@code admin} binding) plus its console-only enforcement knobs (elevation-
 * token lifetime + entry IP allowlist, in {@code admin_console_config}); {@code /user} selects the session policy
 * governing the end-user portal (a {@code PORTAL}/{@code user} binding). Both are per tenant.
 */
@RestController
@RequestMapping("/api/admin/portal-settings")
@RequiredArgsConstructor
public class AdminPortalSettingsController {

    private final PortalSessionBinding portalBinding;
    private final AdminConsoleConfigService consoleConfig;
    private final AdminConsoleSettingsService consoleSettings;
    private final SessionPolicyService sessionPolicies;

    @GetMapping
    @RequirePermission(Permissions.PORTAL_SETTINGS_READ)
    public AdminConsoleSettingsView portalSettings() {
        return consoleView(portalBinding.sessionPolicyId(PortalApps.ADMIN));
    }

    @PutMapping
    @RequirePermission(Permissions.PORTAL_SETTINGS_UPDATE)
    public AdminConsoleSettingsView updatePortalSettings(@Valid @RequestBody AdminConsoleSettingsRequest request) {
        // One transaction for both writes: a malformed CIDR must not leave a new session policy applied with a
        // stale allowlist (a half-applied, fail-open network control).
        consoleSettings.update(request.toPolicyId(), request.elevationTokenTtlMinutes(), request.adminAllowedCidrs());
        return consoleView(portalBinding.sessionPolicyId(PortalApps.ADMIN));
    }

    @GetMapping("/user")
    @RequirePermission(Permissions.PORTAL_SETTINGS_READ)
    public AdminPortalSettingsView userPortalSettings() {
        Optional<UUID> id = portalBinding.sessionPolicyId(PortalApps.USER);
        return AdminPortalSettingsView.of(id, policyName(id));
    }

    @PutMapping("/user")
    @RequirePermission(Permissions.PORTAL_SETTINGS_UPDATE)
    public AdminPortalSettingsView updateUserPortalSettings(@Valid @RequestBody AdminPortalSettingsRequest request) {
        portalBinding.setSessionPolicy(PortalApps.USER, request.toPolicyId());
        Optional<UUID> id = portalBinding.sessionPolicyId(PortalApps.USER);
        return AdminPortalSettingsView.of(id, policyName(id));
    }

    private AdminConsoleSettingsView consoleView(Optional<UUID> policyId) {
        return AdminConsoleSettingsView.of(policyId, policyName(policyId), consoleConfig.current());
    }

    /** The bound policy's display name — resolved here (own-else-global visible) since it may be a global default. */
    private String policyName(Optional<UUID> policyId) {
        return policyId.flatMap(sessionPolicies::findById).map(SessionPolicyDetails::getName).orElse(null);
    }
}
