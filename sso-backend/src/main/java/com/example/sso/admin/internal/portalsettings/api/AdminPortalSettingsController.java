package com.example.sso.admin.internal.portalsettings.api;

import com.example.sso.portal.binding.PortalApps;
import com.example.sso.portal.binding.PortalSessionBinding;
import com.example.sso.shared.security.RequirePermission;
import com.example.sso.user.rbac.Permissions;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin API for a portal's governing session policy. The root path governs the ADMIN CONSOLE (its step-up
 * freshness, elevation TTL, IP allowlist); {@code /user} governs the end-user portal's session lifetime and
 * reauth. Both are all-subjects {@code PORTAL} bindings in the same matrix, per tenant.
 */
@RestController
@RequestMapping("/api/admin/portal-settings")
@RequiredArgsConstructor
public class AdminPortalSettingsController {

    private final PortalSessionBinding portalBinding;

    @GetMapping
    @RequirePermission(Permissions.PORTAL_SETTINGS_READ)
    public AdminPortalSettingsView portalSettings() {
        return AdminPortalSettingsView.of(portalBinding.sessionPolicyId(PortalApps.ADMIN));
    }

    @PutMapping
    @RequirePermission(Permissions.PORTAL_SETTINGS_UPDATE)
    public AdminPortalSettingsView updatePortalSettings(@Valid @RequestBody AdminPortalSettingsRequest request) {
        portalBinding.setSessionPolicy(PortalApps.ADMIN, request.toPolicyId());
        return AdminPortalSettingsView.of(portalBinding.sessionPolicyId(PortalApps.ADMIN));
    }

    @GetMapping("/user")
    @RequirePermission(Permissions.PORTAL_SETTINGS_READ)
    public AdminPortalSettingsView userPortalSettings() {
        return AdminPortalSettingsView.of(portalBinding.sessionPolicyId(PortalApps.USER));
    }

    @PutMapping("/user")
    @RequirePermission(Permissions.PORTAL_SETTINGS_UPDATE)
    public AdminPortalSettingsView updateUserPortalSettings(@Valid @RequestBody AdminPortalSettingsRequest request) {
        portalBinding.setSessionPolicy(PortalApps.USER, request.toPolicyId());
        return AdminPortalSettingsView.of(portalBinding.sessionPolicyId(PortalApps.USER));
    }
}
