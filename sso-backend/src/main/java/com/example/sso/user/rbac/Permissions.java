package com.example.sso.user.rbac;

import com.example.sso.user.role.Roles;

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
    // Network zones (reusable named IP ranges, referenced by session policies)
    public static final String NETWORK_ZONE_READ = "network-zone:read";
    public static final String NETWORK_ZONE_CREATE = "network-zone:create";
    public static final String NETWORK_ZONE_UPDATE = "network-zone:update";
    public static final String NETWORK_ZONE_DELETE = "network-zone:delete";
    // Admin-portal security settings (per-tenant)
    public static final String PORTAL_SETTINGS_READ = "portal-settings:read";
    public static final String PORTAL_SETTINGS_UPDATE = "portal-settings:update";
    // Application assignments (portal)
    public static final String APP_ASSIGNMENT_READ = "app-assignment:read";
    public static final String APP_ASSIGNMENT_ASSIGN = "app-assignment:assign";
    public static final String APP_ASSIGNMENT_UNASSIGN = "app-assignment:unassign";
    // Organizational resources (DAG) + subtree-admin delegation
    public static final String RESOURCE_READ = "resource:read";
    public static final String RESOURCE_CREATE = "resource:create";
    public static final String RESOURCE_UPDATE = "resource:update";
    public static final String RESOURCE_DELETE = "resource:delete";
    public static final String RESOURCE_ASSIGN_ADMIN = "resource:assign-admin";
    // Resource-type vocabulary management (per-tenant since V82): a tenant admin defines its own types.
    public static final String RESOURCE_CREATE_TYPE = "resource:create-type";
    public static final String RESOURCE_DELETE_TYPE = "resource:delete-type";

    // Organizations (tenants) — platform-admin registry management + membership
    public static final String ORG_READ = "organization:read";
    public static final String ORG_CREATE = "organization:create";
    public static final String ORG_UPDATE = "organization:update";
    public static final String ORG_DELETE = "organization:delete";
    public static final String ORG_MEMBER_MANAGE = "organization:member-manage";
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
            NETWORK_ZONE_READ, NETWORK_ZONE_CREATE, NETWORK_ZONE_UPDATE, NETWORK_ZONE_DELETE,
            PORTAL_SETTINGS_READ, PORTAL_SETTINGS_UPDATE,
            APP_ASSIGNMENT_READ, APP_ASSIGNMENT_ASSIGN, APP_ASSIGNMENT_UNASSIGN,
            RESOURCE_READ, RESOURCE_CREATE, RESOURCE_UPDATE, RESOURCE_DELETE, RESOURCE_ASSIGN_ADMIN,
            RESOURCE_CREATE_TYPE, RESOURCE_DELETE_TYPE,
            ORG_READ, ORG_CREATE, ORG_UPDATE, ORG_DELETE, ORG_MEMBER_MANAGE,
            AUDIT_READ, SCIM_MANAGE, KEY_ROTATE);

    private static final Set<String> CATALOG = Set.copyOf(ALL);

    /**
     * Platform-only permissions: the organization registry ({@code organization:create/update/delete}) — only
     * the platform super-admin registers/suspends/deletes tenants. A tenant (org) admin can neither see these
     * in the catalog nor grant them (enforced by {@link PermissionGrantPolicy}) — granting one crosses tenant
     * boundaries.
     *
     * <p>Everything else in {@link #ALL} is tenant-grantable — the directory + policy + application domain a
     * tenant admin owns within their own org: {@code user:*}, {@code group:*}, {@code role:*},
     * {@code auth-policy:*}, {@code session-policy:*}, {@code network-zone:*}, {@code saml-rp:*} and
     * {@code oidc-client:*} (both relying-party types are org-scoped — SAML by {@code org_id}+RLS, OIDC by
     * host-org via {@code OrgScopedRegisteredClientRepository} — so a tenant manages only its own apps),
     * {@code app-assignment:*} (org-scoped portal assignments), {@code resource:*} (the org-scoped resource
     * DAG — a tenant admin manages its own tree STRUCTURE; the GLOBAL resource-type vocabulary and delegating
     * resource-admin to a user stay platform-super-gated in the service), {@code key:rotate} (per-tenant OIDC
     * and SAML signing keys), {@code scim:manage} (org-scoped SCIM tokens that provision INTO its own org and
     * see only its members on {@code /Users}; {@code /Groups} is platform-only, so a tenant token is refused
     * there), plus {@code organization:read}/{@code member-manage} (their own org). {@code portal-settings:*}
     * is org-scoped too: each tenant edits its own admin-console elevation policy (its {@code admin_portal_settings}
     * row), and the elevation gate enforces that tenant's elevation-token TTL by the token's age — decoupled
     * from the single shared admin-console client. {@code audit:read} is org-scoped as well: the audit log READ
     * path resolves the acting tenant, so a tenant admin sees only its own org's events (and an un-drilled
     * super-admin only the global ones) — cross-tenant audit is reached by deliberate drill-in, not this perm.
     */
    public static final Set<String> PLATFORM = Set.of(
            ORG_CREATE, ORG_UPDATE, ORG_DELETE);

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

    /** Whether a permission is platform-only (super-admin exclusive); see {@link #PLATFORM}. */
    public static boolean isPlatform(String permission) {
        return PLATFORM.contains(permission);
    }

    /** The catalog a tenant (org) admin may see and grant — the full catalog minus {@link #PLATFORM}. */
    public static List<String> tenantGrantable() {
        return ALL.stream().filter(perm -> !PLATFORM.contains(perm)).toList();
    }

    private Permissions() {
    }
}
