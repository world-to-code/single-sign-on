package com.example.sso.branding.internal.api;

import com.example.sso.audit.AuditType;
import com.example.sso.audit.Audited;
import com.example.sso.branding.AuthScreen;
import com.example.sso.branding.ScreenCopy;
import com.example.sso.branding.internal.application.ScreenCopyService;
import com.example.sso.shared.security.RequirePermission;
import com.example.sso.shared.security.RequireStepUp;
import com.example.sso.user.rbac.Permissions;
import jakarta.validation.Valid;
import java.util.Map;
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
 * Admin API for the acting tenant's sign-in screen wording. One row per screen, so the screen is a path
 * variable — bound to {@link AuthScreen}, which refuses a name the SPA does not implement before anything is
 * stored. Writes are step-up-gated behind the mutating permission and org-scoped in the service (fail-closed
 * on a bound-orgless non-platform caller); {@code DELETE} reverts that screen to the inherited text.
 */
@RestController
@RequestMapping("/api/admin/branding/screens")
@RequiredArgsConstructor
public class AdminScreenCopyController {

    private final ScreenCopyService service;

    @GetMapping
    @RequirePermission(Permissions.BRANDING_READ)
    public ResponseEntity<Map<AuthScreen, ScreenCopy>> get() {
        return ResponseEntity.ok(service.get());
    }

    @PutMapping("/{screen}")
    @RequirePermission(Permissions.BRANDING_UPDATE)
    @RequireStepUp
    @Audited(AuditType.BRANDING_CHANGED)
    public ResponseEntity<Map<AuthScreen, ScreenCopy>> update(@PathVariable AuthScreen screen,
            @Valid @RequestBody ScreenCopyRequest request) {
        service.update(screen, request.toCopy());
        return ResponseEntity.ok(service.get());
    }

    @DeleteMapping("/{screen}")
    @RequirePermission(Permissions.BRANDING_UPDATE)
    @RequireStepUp
    @Audited(AuditType.BRANDING_CHANGED)
    public ResponseEntity<Void> delete(@PathVariable AuthScreen screen) {
        service.delete(screen);
        return ResponseEntity.noContent().build();
    }
}
