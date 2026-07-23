package com.example.sso.user.internal.application;

import com.example.sso.user.rbac.PermissionPattern;
import com.example.sso.user.rbac.Permissions;
import com.example.sso.user.role.Roles;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Resolves a user's effective permission authorities once negative permissions (deny) exist. Pure and
 * dependency-free: {@link EffectiveAuthorityResolver} reads the deny rows (as platform, so an RLS-invisible
 * deny still applies) and the user's grants/apex, packs them into {@link DenyInputs}, and this decides.
 *
 * <p>Semantics (per the deny plan): assignments UNION, deny SUBTRACTS. A more specific level wins
 * (USER &gt; GROUP = ROLE &gt; ORG); within a level deny beats allow. The ROLE-subject apex carve-out is applied
 * UPSTREAM — {@link EffectiveAuthorityResolver} reads ROLE-subject denies only for the user's APEX (top) roles,
 * so a deny on a role dominated by another role the user holds never reaches this resolver. Effect: a base
 * holder's own role deny bites, but a subordinate cannot cut a superior who holds a higher role. GROUP-subject
 * and USER denies are never carved. The platform veto ({@code org_id} NULL) is absolute — it removes a
 * permission even a USER allow granted. A {@code ROLE_ADMIN} holder is EXEMPT from every deny. Read-implication
 * runs BEFORE subtraction, so a macro (e.g. {@code audit:read}) cannot resurrect a denied finer scope.
 */
@Component
class DenyResolver {

    /** The effective authority strings (permissions + wildcard tokens + role names) for the packed inputs. */
    Set<String> effectiveAuthorities(DenyInputs in) {
        // Super is exempt from every deny — a platform ROLE_ADMIN cannot be vetoed. Its authorities are the
        // plain grant expansion (tokens + members + implied reads), exactly the no-deny path.
        if (in.roleNames().contains(Roles.ADMIN)) {
            return union(Permissions.expandGrants(union(in.userAllow(), in.roleAllow())), in.roleNames());
        }

        DenyRows denies = in.denies();
        Set<String> userAllow = Permissions.expandWildcards(in.userAllow());
        Set<String> roleAllow = Permissions.expandWildcards(in.roleAllow());
        Set<String> userDeny = Permissions.expandWildcards(denies.user());
        Set<String> roleDeny = Permissions.expandWildcards(denies.role());
        Set<String> groupDeny = Permissions.expandWildcards(denies.group());
        Set<String> orgDeny = Permissions.expandWildcards(denies.org());
        Set<String> platformDeny = Permissions.expandWildcards(denies.platform());

        Set<String> allowed = new HashSet<>();
        Set<String> denied = new HashSet<>();
        for (String permission : Permissions.ALL) {
            PermissionDecision decision =
                    decide(permission, userAllow, roleAllow, userDeny, roleDeny, groupDeny, orgDeny);
            if (decision == PermissionDecision.ALLOW) {
                allowed.add(permission);
            } else if (decision == PermissionDecision.DENY) {
                denied.add(permission);
            }
        }

        // Implication BEFORE subtraction: a macro may re-add finer scopes, then the deny removes the ones denied.
        Set<String> effective = new HashSet<>(Permissions.expandImplied(allowed));
        effective.removeAll(denied);
        effective.removeAll(platformDeny); // the platform veto beats even a USER allow

        // Re-add a granted wildcard TOKEN only when EVERY member survived: the grant ceiling checks the token,
        // and you must not hand out a wildcard whose actions were, in part, denied to you.
        return union(effective, survivingWildcardTokens(union(in.userAllow(), in.roleAllow()), effective),
                in.roleNames());
    }

    /** One permission's decision, most-specific level first, deny before allow at each level. */
    private PermissionDecision decide(String permission, Set<String> userAllow, Set<String> roleAllow,
            Set<String> userDeny, Set<String> roleDeny, Set<String> groupDeny, Set<String> orgDeny) {
        if (userDeny.contains(permission)) {
            return PermissionDecision.DENY; // USER level — most specific
        }
        if (userAllow.contains(permission)) {
            return PermissionDecision.ALLOW;
        }
        // ROLE/GROUP level. The apex carve-out is applied UPSTREAM (roleDeny carries only denies on the user's
        // APEX roles — a deny on a role dominated by another held role never reaches here), so a subordinate's
        // deny cannot cut a superior who holds a higher role, while a base holder's own role deny does bite.
        if (roleDeny.contains(permission) || groupDeny.contains(permission)) {
            return PermissionDecision.DENY;
        }
        if (roleAllow.contains(permission)) {
            return PermissionDecision.ALLOW;
        }
        if (orgDeny.contains(permission)) {
            return PermissionDecision.DENY; // ORG level — there is no org-level allow
        }
        return PermissionDecision.SILENT;
    }

    private Set<String> survivingWildcardTokens(Set<String> grantedRaw, Set<String> effective) {
        Set<String> tokens = new HashSet<>();
        for (String name : grantedRaw) {
            if (PermissionPattern.isValid(name) && effective.containsAll(PermissionPattern.of(name).expand())) {
                tokens.add(name);
            }
        }
        return tokens;
    }

    @SafeVarargs
    private Set<String> union(Set<String>... sets) {
        Set<String> result = new HashSet<>();
        for (Set<String> set : sets) {
            result.addAll(set);
        }
        return result;
    }
}
