package com.example.sso.authpolicy.policy;

import com.example.sso.authpolicy.factor.AuthFactor;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable parameter object for {@link AuthPolicyAdminService#update(UUID, AuthPolicyUpdate)}: the
 * editable attributes of an existing authentication policy (the name is fixed and stays a keyed lookup).
 */
public record AuthPolicyUpdate(int priority, boolean enabled, boolean appliesToLogin,
                               boolean allowEnrollmentAtLogin, List<? extends Set<AuthFactor>> steps,
                               Set<UUID> userIds, Set<UUID> roleIds, int stepUpFreshnessMinutes) {
}
