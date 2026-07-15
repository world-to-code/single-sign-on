package com.example.sso.authpolicy.policy;

import com.example.sso.authpolicy.factor.AuthFactor;
import com.example.sso.metadata.AttributePredicate;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable parameter object for {@link AuthPolicyAdminService#update(UUID, AuthPolicyUpdate)}: the
 * editable attributes of an existing authentication policy (the name is fixed and stays a keyed lookup).
 */
public record AuthPolicyUpdate(int priority, boolean enabled, boolean appliesToLogin,
                               boolean allowEnrollmentAtLogin, List<? extends Set<AuthFactor>> steps,
                               Set<UUID> userIds, Set<UUID> roleIds, int stepUpFreshnessMinutes,
                               Set<AttributePredicate> attributePredicates) {

    /** Update with user/role assignments only (no metadata predicate targets). */
    public AuthPolicyUpdate(int priority, boolean enabled, boolean appliesToLogin, boolean allowEnrollmentAtLogin,
                            List<? extends Set<AuthFactor>> steps, Set<UUID> userIds, Set<UUID> roleIds,
                            int stepUpFreshnessMinutes) {
        this(priority, enabled, appliesToLogin, allowEnrollmentAtLogin, steps, userIds, roleIds,
                stepUpFreshnessMinutes, Set.of());
    }
}
