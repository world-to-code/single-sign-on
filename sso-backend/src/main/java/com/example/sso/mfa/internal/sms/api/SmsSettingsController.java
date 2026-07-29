package com.example.sso.mfa.internal.sms.api;

import com.example.sso.mfa.internal.sms.application.SmsSettingsService;
import com.example.sso.mfa.internal.sms.application.SmsSettingsView;
import com.example.sso.shared.security.RequirePermission;
import com.example.sso.shared.security.RequireStepUp;
import com.example.sso.user.rbac.Permissions;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin API for the acting tenant's SMS gateway. The view NEVER carries the API secret; writes are
 * step-up-gated because they are credential-bearing, and org-scoped in the service (fail-closed for a
 * bound-orgless non-platform caller). {@code DELETE} reverts the tier to the platform account.
 */
@RestController
@RequestMapping("/api/admin/sms-settings")
@RequiredArgsConstructor
public class SmsSettingsController {

    private final SmsSettingsService service;

    @GetMapping
    @RequirePermission(Permissions.SMS_SETTINGS_READ)
    public SmsSettingsView get() {
        return service.get();
    }

    @PutMapping
    @RequirePermission(Permissions.SMS_SETTINGS_UPDATE)
    @RequireStepUp
    public SmsSettingsView update(@Valid @RequestBody SmsSettingsRequest request) {
        service.update(request.toSpec());
        return service.get();
    }

    @DeleteMapping
    @RequirePermission(Permissions.SMS_SETTINGS_UPDATE)
    @RequireStepUp
    public SmsSettingsView delete() {
        service.delete();
        return service.get();
    }
}
