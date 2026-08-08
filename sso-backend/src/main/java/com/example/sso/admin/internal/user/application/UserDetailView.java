package com.example.sso.admin.internal.user.application;

import com.example.sso.user.account.UserAccount;
import com.example.sso.user.deny.DenyRow;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Full admin detail for a single user: profile/state, role assignments annotated with their source
 * (direct vs. group-delegated), the directly-granted permissions, the EFFECTIVE permission set (deny-applied —
 * what the user's resolved authorities actually carry) and the permissions {@code deniedPermissions} the user
 * the user does not hold, each carrying the LEVEL that withheld it — the actionable half of "why is this
 * permission absent", and the reason the list is no longer a set subtraction that could name no level.
 *
 * <p>{@code profileId} names the profile whose columns describe this person, so the console can render THEM
 * rather than a generic key/value list. It is nullable: an account created before profiles, or one whose
 * profile was deleted, falls back to the organization's default.
 */
public record UserDetailView(String id, String username, String email, String displayName,
                             boolean enabled, boolean emailVerified, String phoneNumber, boolean phoneVerified,
                             boolean accountNonLocked, String externalId, UUID profileId,
                             Instant createdAt, Instant updatedAt,
                             List<RoleAssignmentView> roleAssignments, List<String> directPermissions,
                             List<HeldPermissionView> effectivePermissions,
                             List<WithheldPermissionView> deniedPermissions,
                             List<DenyRow> userDenies) {

    /** Projects the user plus its pre-computed role/permission roll-ups to the detail view. {@code userDenies}
     *  are the USER-level deny rows the console can lift (a subset of what {@code deniedPermissions} explains). */
    public static UserDetailView of(UserAccount user, List<RoleAssignmentView> roleAssignments,
                                    List<String> directPermissions,
                                    List<HeldPermissionView> effectivePermissions,
                                    List<WithheldPermissionView> deniedPermissions,
                                    List<DenyRow> userDenies) {
        return new UserDetailView(user.getId().toString(), user.getUsername(), user.getEmail(),
                user.getDisplayName(), user.isEnabled(), user.isEmailVerified(), user.getPhoneNumber(),
                user.isPhoneVerified(), user.isAccountNonLocked(), user.getExternalId(), user.getProfileId(),
                user.getCreatedAt(), user.getUpdatedAt(), roleAssignments, directPermissions, effectivePermissions, deniedPermissions,
                userDenies);
    }
}
