package com.example.sso.branding.internal.api;

import com.example.sso.branding.internal.application.BrandingService;
import com.example.sso.branding.internal.application.BrandingView;
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
 * Admin API for the acting tenant's auth-UI branding. Writes are step-up-gated and org-scoped in the service
 * (fail-closed on a bound-orgless non-platform caller); {@code DELETE} reverts the tier to the platform default.
 */
@RestController
@RequestMapping("/api/admin/branding")
@RequiredArgsConstructor
public class AdminBrandingController {

    private final BrandingService service;

    @GetMapping
    @RequirePermission(Permissions.BRANDING_READ)
    public BrandingView get() {
        return service.get();
    }

    @PutMapping
    @RequirePermission(Permissions.BRANDING_UPDATE)
    @RequireStepUp
    public BrandingView update(@Valid @RequestBody BrandingRequest request) {
        service.update(request.toSpec());
        return service.get();
    }

    @DeleteMapping
    @RequirePermission(Permissions.BRANDING_UPDATE)
    @RequireStepUp
    public BrandingView delete() {
        service.delete();
        return service.get();
    }
}
