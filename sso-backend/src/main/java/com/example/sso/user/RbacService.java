package com.example.sso.user;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manages the permission catalog (PBAC) and its assignment to roles (RBAC). The implementation stays
 * module-internal.
 */
public interface RbacService {

    /** Ensures all permissions exist and are granted to ROLE_ADMIN. */
    void grantAllPermissionsToAdmin();

    /** Grants the scoped ROLE_GROUP_ADMIN its baseline user-management permissions (idempotent). */
    void grantGroupAdminPermissions();

    /** Grants the scoped ROLE_ORG_ADMIN its baseline organization-management permissions (idempotent). */
    void grantOrgAdminPermissions();

    /**
     * Provisions the organization's OWN baseline system roles — {@code ROLE_USER},
     * {@code ROLE_GROUP_ADMIN} and {@code ROLE_ORG_ADMIN}, with their baseline (tenant-grantable only)
     * permission grants — idempotently, JOINING the caller's transaction so the roles already exist for a
     * user created later in the same (org-creating) transaction. Returns the provisioned roles' ids by
     * name, e.g. so the caller can grant the org's {@code ROLE_ORG_ADMIN} admin-console entry.
     * {@code ROLE_ADMIN} is deliberately NOT provisioned per org — it is the platform tier's role.
     */
    Map<String, UUID> provisionBaselineRoles(UUID orgId);

    List<String> allPermissions();
}
