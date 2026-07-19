package com.example.sso.federation.internal.api;

import com.example.sso.federation.IdentityProviderService;
import com.example.sso.federation.IdentityProviderView;
import com.example.sso.shared.security.RequirePermission;
import com.example.sso.shared.security.RequireStepUp;
import com.example.sso.user.rbac.Permissions;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin API for the acting tenant's upstream OIDC providers (the view NEVER carries the client secret). Writes
 * are step-up-gated (credential-bearing) and org-scoped in the service (fail-closed on a bound-orgless
 * non-platform caller). Mirrors {@code SmtpSettingsController}.
 */
@RestController
@RequestMapping("/api/admin/identity-providers")
@RequiredArgsConstructor
public class IdentityProviderAdminController {

    private final IdentityProviderService service;

    @GetMapping
    @RequirePermission(Permissions.IDENTITY_PROVIDER_READ)
    public List<IdentityProviderView> list() {
        return service.list();
    }

    @GetMapping("/{alias}")
    @RequirePermission(Permissions.IDENTITY_PROVIDER_READ)
    public IdentityProviderView get(@PathVariable String alias) {
        return service.get(alias);
    }

    @PutMapping("/{alias}")
    @RequirePermission(Permissions.IDENTITY_PROVIDER_WRITE)
    @RequireStepUp
    public IdentityProviderView save(@PathVariable String alias, @Valid @RequestBody IdentityProviderRequest request) {
        service.save(request.toSpec(alias));
        return service.get(alias);
    }

    @DeleteMapping("/{alias}")
    @RequirePermission(Permissions.IDENTITY_PROVIDER_WRITE)
    @RequireStepUp
    public ResponseEntity<Void> delete(@PathVariable String alias) {
        service.delete(alias);
        return ResponseEntity.noContent().build();
    }
}
