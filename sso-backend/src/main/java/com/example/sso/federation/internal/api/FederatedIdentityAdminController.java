package com.example.sso.federation.internal.api;

import com.example.sso.federation.FederatedIdentityAdminService;
import com.example.sso.federation.FederatedIdentityView;
import com.example.sso.shared.security.RequirePermission;
import com.example.sso.shared.security.RequireStepUp;
import com.example.sso.user.rbac.Permissions;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin API for the upstream identities bound to a user. Gated on the USER permissions rather than the
 * identity-provider ones on purpose: this manages one account's credentials, and the permission that registers
 * providers already decides what an upstream may assert — letting it also clear bindings would hand it both
 * halves. Unlinking is step-up gated (credential-bearing) and org-scoped in the service.
 */
@RestController
@RequestMapping("/api/admin/users/{userId}/federated-identities")
@RequiredArgsConstructor
public class FederatedIdentityAdminController {

    private final FederatedIdentityAdminService service;

    @GetMapping
    @RequirePermission(Permissions.USER_READ)
    public List<FederatedIdentityView> list(@PathVariable UUID userId) {
        return service.forUser(userId);
    }

    @DeleteMapping("/{identityId}")
    @RequirePermission(Permissions.USER_UPDATE)
    @RequireStepUp
    public ResponseEntity<Void> unlink(@PathVariable UUID userId, @PathVariable UUID identityId) {
        service.unlink(identityId);
        return ResponseEntity.noContent().build();
    }
}
