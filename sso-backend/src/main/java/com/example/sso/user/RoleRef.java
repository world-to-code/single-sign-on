package com.example.sso.user;

import java.util.Set;
import java.util.UUID;

/**
 * Read-only reference to a role — the user module's public projection of a {@code Role}. Exposes the
 * role's identity and its permission NAMES (not the {@code Permission} entity). The backing entity
 * stays module-internal.
 */
public interface RoleRef {

    UUID getId();

    String getName();

    /** Whether this is a protected system role (name/deletion locked; e.g. ROLE_ADMIN, ROLE_USER). */
    boolean isSystem();

    Set<String> getPermissionNames();
}
