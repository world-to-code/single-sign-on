package com.example.sso.admin.internal.role.api;

import com.example.sso.admin.internal.role.application.PermissionView;
import com.example.sso.admin.internal.role.application.RoleAdminService;
import com.example.sso.admin.internal.role.application.RoleDetailView;
import com.example.sso.admin.internal.role.application.RoleMemberView;
import com.example.sso.admin.internal.role.application.RoleView;
import com.example.sso.admin.internal.shared.security.CanGrantRole;
import com.example.sso.admin.internal.shared.security.CanRevokeRole;
import com.example.sso.shared.security.RequirePermission;
import com.example.sso.shared.security.RequireStepUp;
import com.example.sso.user.rbac.Permissions;
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

/** Admin API for the role builder, its member assignments, and the permission catalog it draws from. */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminRoleController {

    private final RoleAdminService roleAdminService;

    @GetMapping("/roles")
    @RequirePermission(Permissions.ROLE_READ)
    public List<RoleView> roles() {
        return roleAdminService.listRoles();
    }

    @GetMapping("/roles/{id}")
    @RequirePermission(Permissions.ROLE_READ)
    public RoleDetailView role(@PathVariable UUID id) {
        return roleAdminService.roleDetail(id);
    }

    @PutMapping("/roles/{id}/inheritance")
    @RequirePermission(Permissions.ROLE_UPDATE)
    @RequireStepUp
    public RoleDetailView setInheritance(@PathVariable UUID id, @Valid @RequestBody RoleInheritanceRequest request) {
        return roleAdminService.setInheritance(id, request.inheritsFromRoleIds());
    }

    @PostMapping("/roles")
    @RequirePermission(Permissions.ROLE_CREATE)
    @RequireStepUp
    public ResponseEntity<RoleView> createRole(@Valid @RequestBody RoleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(roleAdminService.createRole(request.name(), request.permissions()));
    }

    @PutMapping("/roles/{id}")
    @RequirePermission(Permissions.ROLE_UPDATE)
    @RequireStepUp
    public RoleView updateRole(@PathVariable UUID id, @Valid @RequestBody RoleRequest request) {
        return roleAdminService.updateRole(id, request.name(), request.permissions());
    }

    @DeleteMapping("/roles/{id}")
    @RequirePermission(Permissions.ROLE_DELETE)
    @RequireStepUp
    public ResponseEntity<Void> deleteRole(@PathVariable UUID id) {
        roleAdminService.deleteRole(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/roles/{id}/members")
    @RequirePermission(Permissions.ROLE_READ)
    public List<RoleMemberView> roleMembers(@PathVariable UUID id) {
        return roleAdminService.roleMembers(id);
    }

    @PostMapping("/roles/{id}/members/{userId}")
    @CanGrantRole
    @RequireStepUp
    public ResponseEntity<Void> addRoleMember(@PathVariable UUID id, @PathVariable UUID userId) {
        roleAdminService.addRoleMember(id, userId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/roles/{id}/members/{userId}")
    @CanRevokeRole
    @RequireStepUp
    public ResponseEntity<Void> removeRoleMember(@PathVariable UUID id, @PathVariable UUID userId) {
        roleAdminService.removeRoleMember(id, userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/permissions")
    @RequirePermission(Permissions.ROLE_READ)
    public List<PermissionView> permissions() {
        return roleAdminService.listPermissions();
    }
}
