package com.example.sso.authpolicy.internal.api;

import com.example.sso.authpolicy.internal.application.PolicyAdminService;
import com.example.sso.authpolicy.internal.application.PolicyView;
import com.example.sso.shared.security.RequirePermission;
import com.example.sso.shared.security.RequireStepUp;
import com.example.sso.user.Permissions;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin CRUD for authentication policies (requires the {@code auth-policy:*} permissions). */
@RestController
@RequestMapping("/api/admin/auth-policies")
@RequiredArgsConstructor
public class AuthPolicyAdminController {

    private final PolicyAdminService policies;

    @GetMapping
    @RequirePermission(Permissions.POLICY_READ)
    public List<PolicyView> list() {
        return policies.list();
    }

    @PostMapping
    @RequirePermission(Permissions.POLICY_CREATE)
    @RequireStepUp
    public ResponseEntity<PolicyView> create(@Valid @RequestBody PolicyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(policies.create(request.toSpec()));
    }

    @PutMapping("/{id}")
    @RequirePermission(Permissions.POLICY_UPDATE)
    @RequireStepUp
    public PolicyView update(@PathVariable UUID id, @Valid @RequestBody PolicyRequest request) {
        return policies.update(id, request.toUpdate());
    }

    @DeleteMapping("/{id}")
    @RequirePermission(Permissions.POLICY_DELETE)
    @RequireStepUp
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        policies.delete(id);
        return ResponseEntity.noContent().build();
    }
}
