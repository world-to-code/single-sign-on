package com.example.sso.admin.internal.user.api;

import com.example.sso.admin.internal.shared.security.CanViewUser;
import com.example.sso.federation.FederatedIdentityAdminService;
import com.example.sso.federation.FederatedIdentityView;
import com.example.sso.shared.security.RequireStepUp;
import com.example.sso.user.rbac.Permissions;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The upstream identities a user signs in through. Lives beside the other per-user admin routes rather than
 * with the provider registry, so it composes the SAME instance-level gates they do: permission alone would let
 * a resource-scoped delegate read and revoke the bindings of any account in the tenant, including an
 * administrator's — and revoking one terminates that person's live sessions.
 *
 * <p>Gated on the USER permissions, not the identity-provider ones: whoever registers providers already decides
 * what an upstream may assert, and letting that role also clear bindings would hand it both halves of a
 * takeover. Unlinking is step-up gated, being credential-bearing.
 */
@RestController
@RequestMapping("/api/admin/users/{id}/federated-identities")
@RequiredArgsConstructor
public class AdminFederatedIdentityController {

    private final FederatedIdentityAdminService service;

    @GetMapping
    @CanViewUser
    public List<FederatedIdentityView> list(@PathVariable UUID id) {
        return service.forUser(id);
    }

    // NOT @CanUpdateUser: that annotation also evaluates #request.enabled()/#request.roles(), and this route
    // has no body — an unresolved SpEL argument is exactly the fail-open shape the rules forbid. Spelled out
    // with the two checks that do apply: the mutating permission, and reachability of the target account.
    @DeleteMapping("/{identityId}")
    @PreAuthorize("hasAuthority('" + Permissions.USER_UPDATE + "') and @adminAccessPolicy.canAccessUser(#id)")
    @RequireStepUp
    public ResponseEntity<Void> unlink(@PathVariable UUID id, @PathVariable UUID identityId) {
        service.unlink(id, identityId);
        return ResponseEntity.noContent().build();
    }
}
