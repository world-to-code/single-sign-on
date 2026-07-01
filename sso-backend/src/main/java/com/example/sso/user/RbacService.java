package com.example.sso.user;

import java.util.List;

/**
 * Manages the permission catalog (PBAC) and its assignment to roles (RBAC). The implementation stays
 * module-internal.
 */
public interface RbacService {

    /** Ensures all permissions exist and are granted to ROLE_ADMIN. */
    void grantAllPermissionsToAdmin();

    List<String> allPermissions();
}
