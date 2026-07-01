package com.example.sso.user;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Single source of truth for the fine-grained permission names (PBAC), in {@code resource:action}
 * form. Referenced by the seeded catalog ({@link RbacService}), by method-security expressions via
 * constant concatenation (e.g. {@code @PreAuthorize("hasAuthority('" + Permissions.USER_READ + "')")}),
 * and by role/permission administration. A mutating action on a resource IMPLIES read on that
 * resource (see {@link #expandImplied}) — e.g. holding {@code oidc-client:create} also grants
 * {@code oidc-client:read}.
 */
public final class Permissions {

    // Users
    public static final String USER_READ = "user:read";
    public static final String USER_CREATE = "user:create";
    public static final String USER_UPDATE = "user:update";
    public static final String USER_DELETE = "user:delete";
    // Roles
    public static final String ROLE_READ = "role:read";
    public static final String ROLE_CREATE = "role:create";
    public static final String ROLE_UPDATE = "role:update";
    public static final String ROLE_DELETE = "role:delete";
    // Groups
    public static final String GROUP_READ = "group:read";
    public static final String GROUP_CREATE = "group:create";
    public static final String GROUP_UPDATE = "group:update";
    public static final String GROUP_DELETE = "group:delete";
    // OIDC clients
    public static final String CLIENT_READ = "oidc-client:read";
    public static final String CLIENT_CREATE = "oidc-client:create";
    public static final String CLIENT_UPDATE = "oidc-client:update";
    public static final String CLIENT_DELETE = "oidc-client:delete";
    // SAML relying parties
    public static final String SAML_READ = "saml-rp:read";
    public static final String SAML_CREATE = "saml-rp:create";
    public static final String SAML_UPDATE = "saml-rp:update";
    public static final String SAML_DELETE = "saml-rp:delete";
    // Authentication policies
    public static final String POLICY_READ = "auth-policy:read";
    public static final String POLICY_CREATE = "auth-policy:create";
    public static final String POLICY_UPDATE = "auth-policy:update";
    public static final String POLICY_DELETE = "auth-policy:delete";
    // Session policies
    public static final String SESSION_POLICY_READ = "session-policy:read";
    public static final String SESSION_POLICY_CREATE = "session-policy:create";
    public static final String SESSION_POLICY_UPDATE = "session-policy:update";
    public static final String SESSION_POLICY_DELETE = "session-policy:delete";
    // IP access rules
    public static final String IP_RULE_READ = "ip-rule:read";
    public static final String IP_RULE_CREATE = "ip-rule:create";
    public static final String IP_RULE_UPDATE = "ip-rule:update";
    public static final String IP_RULE_DELETE = "ip-rule:delete";
    // Admin-portal security settings (singleton)
    public static final String PORTAL_SETTINGS_READ = "portal-settings:read";
    public static final String PORTAL_SETTINGS_UPDATE = "portal-settings:update";
    // Application assignments (portal)
    public static final String APP_ASSIGNMENT_READ = "app-assignment:read";
    public static final String APP_ASSIGNMENT_ASSIGN = "app-assignment:assign";
    public static final String APP_ASSIGNMENT_UNASSIGN = "app-assignment:unassign";
    // Single-action resources
    public static final String AUDIT_READ = "audit:read";
    public static final String SCIM_MANAGE = "scim:manage";
    public static final String KEY_ROTATE = "key:rotate";

    /** Full catalog, grouped by resource in a stable order. */
    public static final List<String> ALL = List.of(
            USER_READ, USER_CREATE, USER_UPDATE, USER_DELETE,
            ROLE_READ, ROLE_CREATE, ROLE_UPDATE, ROLE_DELETE,
            GROUP_READ, GROUP_CREATE, GROUP_UPDATE, GROUP_DELETE,
            CLIENT_READ, CLIENT_CREATE, CLIENT_UPDATE, CLIENT_DELETE,
            SAML_READ, SAML_CREATE, SAML_UPDATE, SAML_DELETE,
            POLICY_READ, POLICY_CREATE, POLICY_UPDATE, POLICY_DELETE,
            SESSION_POLICY_READ, SESSION_POLICY_CREATE, SESSION_POLICY_UPDATE, SESSION_POLICY_DELETE,
            IP_RULE_READ, IP_RULE_CREATE, IP_RULE_UPDATE, IP_RULE_DELETE,
            PORTAL_SETTINGS_READ, PORTAL_SETTINGS_UPDATE,
            APP_ASSIGNMENT_READ, APP_ASSIGNMENT_ASSIGN, APP_ASSIGNMENT_UNASSIGN,
            AUDIT_READ, SCIM_MANAGE, KEY_ROTATE);

    private static final Set<String> CATALOG = Set.copyOf(ALL);

    /**
     * Expands a set of granted permissions with the implied {@code <resource>:read}: any mutating
     * action (anything other than {@code read}) on a resource that HAS a read permission also grants
     * read. Applied when building a user's effective authorities so that, e.g., a create/update/delete
     * holder can also list the resource without read being assigned explicitly.
     */
    public static Set<String> expandImplied(Collection<String> granted) {
        Set<String> result = new HashSet<>(granted);

        for (String perm : granted) {
            int sep = perm.indexOf(':');
            if (sep <= 0 || "read".equals(perm.substring(sep + 1))) {
                continue; // no resource, or already a read permission
            }

            String read = perm.substring(0, sep) + ":read";
            if (CATALOG.contains(read)) {
                result.add(read);
            }
        }

        return result;
    }

    private Permissions() {
    }
}
