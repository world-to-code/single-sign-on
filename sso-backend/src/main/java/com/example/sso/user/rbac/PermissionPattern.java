package com.example.sso.user.rbac;

import com.example.sso.shared.error.BadRequestException;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A permission WILDCARD, parsed and validated from its token form. Two shapes exist:
 *
 * <ul>
 *   <li>{@code <resource>:*} — every action on ONE resource, for a tenant's convenience (grant "all of user"
 *       without enumerating four rows). It expands to that resource's two-segment catalog members ONLY, and is
 *       valid only when none of them is platform-only and the resource has no finer three-segment sub-scopes
 *       (which a two-segment wildcard could not honour — this is why {@code audit} has no wildcard);</li>
 *   <li>{@code *:*} — the super token: EVERY permission, platform included. Held by {@code ROLE_ADMIN}. It is a
 *       platform-tier grant ({@link Permissions#isPlatformGrant}) — a tenant may never hold or grant it.</li>
 * </ul>
 *
 * <p>A wildcard is stored as its token; a holder's EFFECTIVE authorities carry the token AND its expansion
 * ({@link Permissions#expandGrants}), so an exact-match {@code hasAuthority(...)} endpoint check is satisfied by
 * the expansion while the grant-ceiling "must hold what you hand out" check still sees the token.
 */
public final class PermissionPattern {

    /** The super token: expands to the whole catalog, platform included. */
    public static final String SUPER = "*:*";

    private static final String WILDCARD_ACTION = "*";

    private final String token;
    private final Set<String> expansion;

    private PermissionPattern(String token, Set<String> expansion) {
        this.token = token;
        this.expansion = expansion;
    }

    /** Whether {@code name} is shaped as a wildcard ({@code <x>:*}) — NOT whether it is a VALID one. */
    public static boolean isWildcardToken(String name) {
        if (name == null) {
            return false;
        }
        int colon = name.indexOf(':');
        // The action after the first colon must be EXACTLY "*" — which already implies a single colon (a
        // multi-colon remainder like "*:*" or "read:*" can never equal "*"), so no separate colon-count check.
        return colon > 0 && WILDCARD_ACTION.equals(name.substring(colon + 1));
    }

    /** Whether {@code name} is a wildcard token that actually resolves to something grantable. */
    public static boolean isValid(String name) {
        if (!isWildcardToken(name)) {
            return false;
        }
        if (SUPER.equals(name)) {
            return true;
        }
        // Catalog-shape questions belong to the catalog: a resource wildcard is valid only when the resource has
        // two-segment actions, carries no finer sub-scope (which a two-segment wildcard cannot honour), and none
        // of its actions is platform-only (so a tenant wildcard can never reach the platform tier).
        String resource = name.substring(0, name.indexOf(':'));
        Set<String> members = Permissions.actionsOf(resource);
        return !members.isEmpty()
                && !Permissions.hasSubScopes(resource)
                && members.stream().noneMatch(Permissions::isPlatform);
    }

    /** Parses a wildcard token, rejecting an invalid one as a 400 so a bad pattern never reaches storage. */
    public static PermissionPattern of(String token) {
        if (!isValid(token)) {
            throw BadRequestException.of("user.permission.wildcardInvalid", token);
        }
        Set<String> expansion = SUPER.equals(token)
                ? new LinkedHashSet<>(Permissions.ALL)
                : Permissions.actionsOf(token.substring(0, token.indexOf(':')));
        return new PermissionPattern(token, expansion);
    }

    /** The wildcard token for {@code resource} ({@code <resource>:*}) — not necessarily {@link #isValid}. */
    public static String resourceWildcard(String resource) {
        return resource + ":" + WILDCARD_ACTION;
    }

    /** The super token grants everything; a resource wildcard grants only its own resource. */
    public boolean isSuper() {
        return SUPER.equals(token);
    }

    /** The concrete catalog permissions this wildcard stands for (unmodifiable). */
    public Set<String> expand() {
        return Set.copyOf(expansion);
    }
}
