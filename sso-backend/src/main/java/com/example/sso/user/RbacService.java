package com.example.sso.user;

import java.util.List;

/**
 * Manages the permission catalog (PBAC) and its assignment to roles (RBAC). The implementation stays
 * module-internal.
 */
public interface RbacService {

    /** Ensures all permissions exist and are granted to ROLE_ADMIN. */
    void grantAllPermissionsToAdmin();

    /** Grants the scoped ROLE_GROUP_ADMIN its baseline user-management permissions (idempotent). */
    void grantGroupAdminPermissions();

    List<String> allPermissions();
}
