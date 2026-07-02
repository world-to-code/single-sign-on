package com.example.sso.admin.internal.portalsettings.api;

import com.example.sso.admin.AdminPortalSettingsService;
import com.example.sso.admin.internal.portalsettings.application.AdminPortalSettingsRequest;
import com.example.sso.shared.security.RequirePermission;
import com.example.sso.user.Permissions;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin API for admin-portal security settings (elevation freshness, admin session lifetimes, IP allowlist). */
@RestController
@RequestMapping("/api/admin/portal-settings")
@RequiredArgsConstructor
public class AdminPortalSettingsController {

    private final AdminPortalSettingsService adminPortalSettings;

    @GetMapping
    @RequirePermission(Permissions.PORTAL_SETTINGS_READ)
    public AdminPortalSettingsView portalSettings() {
        return AdminPortalSettingsView.of(adminPortalSettings.get());
    }

    @PutMapping
    @RequirePermission(Permissions.PORTAL_SETTINGS_UPDATE)
    public AdminPortalSettingsView updatePortalSettings(@Valid @RequestBody AdminPortalSettingsRequest request) {
        return AdminPortalSettingsView.of(adminPortalSettings.update(request));
    }
}
