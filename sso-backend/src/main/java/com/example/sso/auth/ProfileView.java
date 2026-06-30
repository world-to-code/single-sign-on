package com.example.sso.auth;

import java.util.List;

/**
 * The signed-in user's own account summary for the self-service "My Profile" page: identity plus a
 * roll-up of their security factors (email verification, TOTP, FIDO2 passkeys) and roles.
 */
public record ProfileView(String username, String email, String displayName, boolean emailVerified,
                          boolean totpEnrolled, boolean fido2Enrolled, int passkeyCount, List<String> roles) {
}
