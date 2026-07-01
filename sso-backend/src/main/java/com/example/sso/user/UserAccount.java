package com.example.sso.user;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only view of a user account — the user module's public projection, consumed by auth, MFA,
 * OIDC/SAML, SCIM, portal and admin. It exposes identity/profile/state and role/permission NAMES, but
 * never the password hash or the mutable entity. State changes go through {@link UserService} behavior
 * methods; the backing {@code AppUser} entity stays module-internal.
 */
public interface UserAccount {

    UUID getId();

    String getUsername();

    String getEmail();

    String getDisplayName();

    boolean isEnabled();

    boolean isEmailVerified();

    boolean isAccountNonLocked();

    /** True while a temporary brute-force lockout is in effect. */
    boolean isTemporarilyLocked(Instant now);

    String getExternalId();

    Instant getCreatedAt();

    Instant getUpdatedAt();

    Set<? extends RoleRef> getRoles();

    /** Names of permissions granted DIRECTLY to the user (not via roles). */
    Set<String> getDirectPermissionNames();
}
