package com.example.sso.email.internal.api;

import com.example.sso.email.internal.application.SmtpSettingsService;
import com.example.sso.email.internal.application.SmtpSettingsView;
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
 * Admin API for the acting tenant's SMTP relay (the view NEVER carries the password). Writes are step-up-gated
 * (credential-bearing) and org-scoped in the service (fail-closed on a bound-orgless non-platform caller).
 * {@code DELETE} reverts the tier to the platform default.
 */
@RestController
@RequestMapping("/api/admin/smtp-settings")
@RequiredArgsConstructor
public class SmtpSettingsController {

    private final SmtpSettingsService service;

    @GetMapping
    @RequirePermission(Permissions.SMTP_SETTINGS_READ)
    public SmtpSettingsView get() {
        return service.get();
    }

    @PutMapping
    @RequirePermission(Permissions.SMTP_SETTINGS_UPDATE)
    @RequireStepUp
    public SmtpSettingsView update(@Valid @RequestBody SmtpSettingsRequest request) {
        service.update(request.toSpec());
        return service.get();
    }

    @DeleteMapping
    @RequirePermission(Permissions.SMTP_SETTINGS_UPDATE)
    @RequireStepUp
    public SmtpSettingsView delete() {
        service.delete();
        return service.get();
    }
}
