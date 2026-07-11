package com.example.sso.authpolicy.policy;

import com.example.sso.authpolicy.factor.AuthFactor;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable parameter object for {@link AuthPolicyAdminService#create(AuthPolicySpec)}: the full set
 * of attributes for a new authentication policy, including its name, steps and assignment sets.
 */
public record AuthPolicySpec(String name, int priority, boolean enabled, boolean appliesToLogin,
                             boolean allowEnrollmentAtLogin, List<? extends Set<AuthFactor>> steps,
                             Set<UUID> userIds, Set<UUID> roleIds, int stepUpFreshnessMinutes) {
}
