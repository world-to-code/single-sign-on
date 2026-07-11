package com.example.sso.auth.internal.profile.application;

import com.example.sso.user.RoleRef;
import com.example.sso.user.UserAccount;
import java.util.List;

/**
 * The signed-in user's own account summary for the self-service "My Profile" page: identity plus a
 * roll-up of their security factors (email verification, TOTP, FIDO2 passkeys) and roles.
 */
public record ProfileView(String username, String email, String displayName, boolean emailVerified,
                          boolean totpEnrolled, boolean fido2Enrolled, int passkeyCount, List<String> roles) {

    /** Projects the user plus their factor roll-up (TOTP enabled, passkey count) to the profile view. */
    public static ProfileView of(UserAccount user, boolean totpEnrolled, int passkeyCount) {
        List<String> roles = user.getRoles().stream().map(RoleRef::getName).sorted().toList();
        return new ProfileView(user.getUsername(), user.getEmail(), user.getDisplayName(), user.isEmailVerified(),
                totpEnrolled, passkeyCount > 0, passkeyCount, roles);
    }
}
