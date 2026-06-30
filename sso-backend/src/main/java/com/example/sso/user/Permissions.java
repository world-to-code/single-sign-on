package com.example.sso.user;

import java.util.List;

/**
 * Single source of truth for the fine-grained permission names (PBAC). Referenced both by the
 * seeded catalog ({@link RbacService}) and by method-security expressions via constant
 * concatenation (e.g. {@code @PreAuthorize("hasAuthority('" + Permissions.USER_READ + "')")}),
 * so a rename is caught at compile time in every place.
 */
public final class Permissions {

    public static final String USER_READ = "user:read";
    public static final String USER_WRITE = "user:write";
    public static final String ROLE_READ = "role:read";
    public static final String POLICY_MANAGE = "policy:manage";
    public static final String CLIENT_READ = "client:read";
    public static final String CLIENT_WRITE = "client:write";
    public static final String SAML_READ = "saml:read";
    public static final String SAML_WRITE = "saml:write";
    public static final String SCIM_MANAGE = "scim:manage";
    public static final String AUDIT_READ = "audit:read";
    public static final String KEY_ROTATE = "key:rotate";
    public static final String SESSION_MANAGE = "session:manage";
    public static final String APP_ASSIGN = "app:assign";

    /** Full catalog, in a stable order. */
    public static final List<String> ALL = List.of(
            USER_READ, USER_WRITE, ROLE_READ, POLICY_MANAGE,
            CLIENT_READ, CLIENT_WRITE, SAML_READ, SAML_WRITE, SCIM_MANAGE, AUDIT_READ, KEY_ROTATE,
            SESSION_MANAGE, APP_ASSIGN);

    private Permissions() {
    }
}
