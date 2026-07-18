package com.example.sso.admin.internal.user.application;

import com.example.sso.user.account.UserAccount;
import java.time.Instant;
import java.util.List;

/**
 * Full admin detail for a single user: profile/state, role assignments annotated with their source
 * (direct vs. group-delegated), the directly-granted permissions, and the effective permission set
 * (all role + group + direct permissions, read-implication expanded).
 */
public record UserDetailView(String id, String username, String email, String displayName,
                             boolean enabled, boolean emailVerified, String phoneNumber, boolean phoneVerified,
                             boolean accountNonLocked, String externalId, Instant createdAt, Instant updatedAt,
                             List<RoleAssignmentView> roleAssignments, List<String> directPermissions,
                             List<String> effectivePermissions) {

    /** Projects the user plus its pre-computed role/permission roll-ups to the detail view. */
    public static UserDetailView of(UserAccount user, List<RoleAssignmentView> roleAssignments,
                                    List<String> directPermissions, List<String> effectivePermissions) {
        return new UserDetailView(user.getId().toString(), user.getUsername(), user.getEmail(),
                user.getDisplayName(), user.isEnabled(), user.isEmailVerified(), user.getPhoneNumber(),
                user.isPhoneVerified(), user.isAccountNonLocked(), user.getExternalId(), user.getCreatedAt(),
                user.getUpdatedAt(), roleAssignments, directPermissions, effectivePermissions);
    }
}
