package com.example.sso.user;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Manages the permission catalog (PBAC) and its assignment to roles (RBAC).
 */
@Service
@RequiredArgsConstructor
public class RbacService {

    /** The fine-grained permissions used by method-level {@code @PreAuthorize} policies (see {@link Permissions}). */
    public static final List<String> ALL_PERMISSIONS = Permissions.ALL;

    private final PermissionRepository permissions;
    private final RoleRepository roles;

    @Transactional
    public Permission getOrCreatePermission(String name) {
        return permissions.findByName(name).orElseGet(() -> permissions.save(new Permission(name)));
    }

    /** Ensures all permissions exist and are granted to ROLE_ADMIN. */
    @Transactional
    public void grantAllPermissionsToAdmin() {
        Role admin = roles.findByName("ROLE_ADMIN")
                .orElseThrow(() -> new IllegalStateException("ROLE_ADMIN must exist before granting permissions"));
        ALL_PERMISSIONS.forEach(name -> admin.addPermission(getOrCreatePermission(name)));
        roles.save(admin);
    }

    @Transactional(readOnly = true)
    public List<String> allPermissions() {
        return ALL_PERMISSIONS;
    }
}
