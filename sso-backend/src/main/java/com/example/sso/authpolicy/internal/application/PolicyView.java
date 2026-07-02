package com.example.sso.authpolicy.internal.application;

import com.example.sso.authpolicy.AuthFactor;
import com.example.sso.authpolicy.AuthPolicyView;
import java.util.List;
import java.util.UUID;

/** Admin view of an authentication policy. steps = ordered list of allowed-factor choices. */
public record PolicyView(String id, String name, int priority, boolean enabled, boolean appliesToLogin,
                         boolean allowEnrollmentAtLogin,
                         List<List<String>> steps, List<String> assignedUserIds, List<String> assignedRoleIds,
                         int stepUpFreshnessMinutes) {

    /** Projects a domain policy to its admin view (factor enums to sorted names, ids to strings). */
    public static PolicyView of(AuthPolicyView policy) {
        List<List<String>> steps = policy.getSteps().stream()
                .map(step -> step.getAllowedFactors().stream().map(AuthFactor::name).sorted().toList())
                .toList();
        return new PolicyView(policy.getId().toString(), policy.getName(), policy.getPriority(), policy.isEnabled(),
                policy.isAppliesToLogin(), policy.isAllowEnrollmentAtLogin(), steps,
                policy.getAssignedUserIds().stream().map(UUID::toString).toList(),
                policy.getAssignedRoleIds().stream().map(UUID::toString).toList(),
                policy.getStepUpFreshnessMinutes());
    }
}
