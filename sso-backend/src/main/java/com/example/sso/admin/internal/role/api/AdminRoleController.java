package com.example.sso.admin.internal.role.api;

import com.example.sso.admin.internal.role.application.PermissionView;
import com.example.sso.admin.internal.role.application.RoleView;
import com.example.sso.admin.internal.user.application.UserAdminService;
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

/** Admin API for the role builder and the permission catalog it draws from. */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminRoleController {

    private final UserAdminService userAdminService;

    @GetMapping("/roles")
    @RequirePermission(Permissions.ROLE_READ)
    public List<RoleView> roles() {
        return userAdminService.listRoles();
    }

    @PostMapping("/roles")
    @RequirePermission(Permissions.ROLE_CREATE)
    @RequireStepUp
    public ResponseEntity<RoleView> createRole(@Valid @RequestBody RoleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(userAdminService.createRole(request.name(), request.permissions()));
    }

    @PutMapping("/roles/{id}")
    @RequirePermission(Permissions.ROLE_UPDATE)
    @RequireStepUp
    public RoleView updateRole(@PathVariable UUID id, @Valid @RequestBody RoleRequest request) {
        return userAdminService.updateRole(id, request.name(), request.permissions());
    }

    @DeleteMapping("/roles/{id}")
    @RequirePermission(Permissions.ROLE_DELETE)
    @RequireStepUp
    public ResponseEntity<Void> deleteRole(@PathVariable UUID id) {
        userAdminService.deleteRole(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/permissions")
    @RequirePermission(Permissions.ROLE_READ)
    public List<PermissionView> permissions() {
        return userAdminService.listPermissions();
    }
}
