package com.example.sso.user.internal.application;

import com.example.sso.user.internal.rbac.PermissionPattern;
import com.example.sso.user.rbac.DecisionReason;
import com.example.sso.user.rbac.Permissions;
import com.example.sso.user.role.Roles;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.stream.Collectors;
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


    /**
     * Every catalog permission's verdict for these inputs — the ladder's own answer, before the wildcard and
     * implication folding that turns it into an authority set.
     *
     * <p>This is the SAME computation {@link #effectiveAuthorities} folds over, not a parallel one. An
     * explanation produced beside the decision rather than by it would be free to disagree with what the
     * system actually did, which is the failure this method's existence prevents.
     */
    Map<String, PermissionVerdict> verdicts(DenyInputs in) {
        if (in.roleNames().contains(Roles.ADMIN)) {
            return Permissions.ALL.stream().collect(Collectors.toMap(permission -> permission,
                    permission -> PermissionVerdict.of(PermissionDecision.ALLOW,
                            DecisionReason.SUPER_ADMIN_EXEMPT)));
        }

        DenyRows denies = in.denies();
        Set<String> userAllow = Permissions.expandWildcardMembers(in.userAllow());
        Set<String> roleAllow = Permissions.expandWildcardMembers(in.roleAllow());
        Set<String> userDeny = Permissions.expandWildcardMembers(denies.user());
        Set<String> roleDeny = Permissions.expandWildcardMembers(denies.role());
        Set<String> groupDeny = Permissions.expandWildcardMembers(denies.group());
        Set<String> orgDeny = Permissions.expandWildcardMembers(denies.org());
        Set<String> platformDeny = Permissions.expandWildcardMembers(denies.platform());

        Map<String, PermissionVerdict> verdicts = new HashMap<>();
        for (String permission : Permissions.ALL) {
            // The veto is absolute and sits OUTSIDE the ladder, so it is applied here rather than inside
            // decide(): otherwise a vetoed permission would be credited to whichever grant appeared to win.
            verdicts.put(permission, platformDeny.contains(permission)
                    ? PermissionVerdict.of(PermissionDecision.DENY, DecisionReason.PLATFORM_VETO)
                    : decide(permission, userAllow, roleAllow, userDeny, roleDeny, groupDeny, orgDeny));
        }
        return verdicts;
    }

    /** The effective authority strings (permissions + wildcard tokens + role names) for the packed inputs. */
    Set<String> effectiveAuthorities(DenyInputs in) {
        // Super is exempt from every deny — a platform ROLE_ADMIN cannot be vetoed. Its authorities are the
        // plain grant expansion (tokens + members + implied reads), exactly the no-deny path.
        if (in.roleNames().contains(Roles.ADMIN)) {
            return union(Permissions.expandGrantedAuthorities(union(in.userAllow(), in.roleAllow())), in.roleNames());
        }

        // Folded from the SAME verdicts an explanation reads, so the two can never describe different
        // decisions. The veto is already inside them, hence no separate platform subtraction below.
        Set<String> allowed = new HashSet<>();
        Set<String> denied = new HashSet<>();
        verdicts(in).forEach((permission, verdict) -> {
            if (verdict.decision() == PermissionDecision.ALLOW) {
                allowed.add(permission);
            } else if (verdict.decision() == PermissionDecision.DENY) {
                denied.add(permission);
            }
        });

        // Implication BEFORE subtraction: a macro may re-add finer scopes, then the deny removes the ones denied.
        Set<String> effective = new HashSet<>(Permissions.expandImplied(allowed));
        effective.removeAll(denied); // the veto is already among these — verdicts() applies it above

        // Re-add a granted wildcard TOKEN only when EVERY member survived: the grant ceiling checks the token,
        // and you must not hand out a wildcard whose actions were, in part, denied to you.
        return union(effective, survivingWildcardTokens(union(in.userAllow(), in.roleAllow()), effective),
                in.roleNames());
    }

    /**
     * One permission's verdict, most-specific level first, deny before allow at each level.
     *
     * <p>The reason is set by the branch that decides, never inferred from the decision afterwards — several
     * rungs reach the same outcome for opposite causes, so an inferred reason would be a guess dressed as a
     * fact.
     */
    private PermissionVerdict decide(String permission, Set<String> userAllow, Set<String> roleAllow,
            Set<String> userDeny, Set<String> roleDeny, Set<String> groupDeny, Set<String> orgDeny) {
        if (userDeny.contains(permission)) {
            return PermissionVerdict.of(PermissionDecision.DENY, DecisionReason.DENIED_AT_USER_LEVEL);
        }
        if (userAllow.contains(permission)) {
            return PermissionVerdict.of(PermissionDecision.ALLOW, DecisionReason.ALLOWED_AT_USER_LEVEL);
        }
        // ROLE/GROUP level. The apex carve-out is applied UPSTREAM (roleDeny carries only denies on the user's
        // APEX roles — a deny on a role dominated by another held role never reaches here), so a subordinate's
        // deny cannot cut a superior who holds a higher role, while a base holder's own role deny does bite.
        if (roleDeny.contains(permission)) {
            return PermissionVerdict.of(PermissionDecision.DENY, DecisionReason.DENIED_AT_ROLE_LEVEL);
        }
        if (groupDeny.contains(permission)) {
            return PermissionVerdict.of(PermissionDecision.DENY, DecisionReason.DENIED_AT_GROUP_LEVEL);
        }
        if (roleAllow.contains(permission)) {
            return PermissionVerdict.of(PermissionDecision.ALLOW, DecisionReason.ALLOWED_AT_ROLE_LEVEL);
        }
        if (orgDeny.contains(permission)) {
            return PermissionVerdict.of(PermissionDecision.DENY, DecisionReason.DENIED_AT_ORG_LEVEL);
        }
        return PermissionVerdict.of(PermissionDecision.SILENT, DecisionReason.NO_LEVEL_SPOKE);
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
