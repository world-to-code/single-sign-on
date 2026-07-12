package com.example.sso.admin.internal.portalsettings.api;

import com.example.sso.portal.binding.AdminConsoleBinding;
import com.example.sso.shared.security.RequirePermission;
import com.example.sso.user.rbac.Permissions;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin API for the admin console's governing session policy (its step-up freshness, elevation TTL, IP allowlist). */
@RestController
@RequestMapping("/api/admin/portal-settings")
@RequiredArgsConstructor
public class AdminPortalSettingsController {

    private final AdminConsoleBinding consoleBinding;

    @GetMapping
    @RequirePermission(Permissions.PORTAL_SETTINGS_READ)
    public AdminPortalSettingsView portalSettings() {
        return AdminPortalSettingsView.of(consoleBinding.sessionPolicyId());
    }

    @PutMapping
    @RequirePermission(Permissions.PORTAL_SETTINGS_UPDATE)
    public AdminPortalSettingsView updatePortalSettings(@Valid @RequestBody AdminPortalSettingsRequest request) {
        consoleBinding.setSessionPolicy(request.toPolicyId());
        return AdminPortalSettingsView.of(consoleBinding.sessionPolicyId());
    }
}
