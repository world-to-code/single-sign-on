package com.example.sso.user.account;

import com.example.sso.user.role.RoleRef;

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

    /** The organization (the tenant) that owns this user's identity; {@code null} = the global platform
     *  super-admin. The organization is the identity boundary. */
    UUID getOrgId();

    String getUsername();

    String getEmail();

    String getDisplayName();

    boolean isEnabled();

    boolean isEmailVerified();

    /** True when this user was given a temporary password and must set their own on first login. */
    boolean isPasswordResetRequired();

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
