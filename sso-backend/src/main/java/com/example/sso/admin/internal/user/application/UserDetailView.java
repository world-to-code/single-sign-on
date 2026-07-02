package com.example.sso.admin.internal.user.application;

import java.time.Instant;
import java.util.List;

/**
 * Full admin detail for a single user: profile/state, role assignments annotated with their source
 * (direct vs. group-delegated), the directly-granted permissions, and the effective permission set
 * (all role + group + direct permissions, read-implication expanded).
 */
public record UserDetailView(String id, String username, String email, String displayName,
                             boolean enabled, boolean emailVerified, boolean accountNonLocked,
                             String externalId, Instant createdAt, Instant updatedAt,
                             List<RoleAssignmentView> roleAssignments, List<String> directPermissions,
                             List<String> effectivePermissions) {
}
