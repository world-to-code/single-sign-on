package com.example.sso.authpolicy.policy;

import com.example.sso.authpolicy.factor.AuthFactor;
import com.example.sso.metadata.AttributePredicate;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable parameter object for {@link AuthPolicyAdminService#create(AuthPolicySpec)}: the full set
 * of attributes for a new authentication policy, including its name, steps and assignment sets (users, roles,
 * metadata predicates).
 */
public record AuthPolicySpec(String name, int priority, boolean enabled, boolean appliesToLogin,
                             boolean allowEnrollmentAtLogin, List<? extends Set<AuthFactor>> steps,
                             Set<UUID> userIds, Set<UUID> roleIds, int stepUpFreshnessMinutes,
                             Set<AttributePredicate> attributePredicates) {

    /** Create with user/role assignments only (no metadata predicate targets). */
    public AuthPolicySpec(String name, int priority, boolean enabled, boolean appliesToLogin,
                          boolean allowEnrollmentAtLogin, List<? extends Set<AuthFactor>> steps,
                          Set<UUID> userIds, Set<UUID> roleIds, int stepUpFreshnessMinutes) {
        this(name, priority, enabled, appliesToLogin, allowEnrollmentAtLogin, steps, userIds, roleIds,
                stepUpFreshnessMinutes, Set.of());
    }
}
