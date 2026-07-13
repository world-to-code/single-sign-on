package com.example.sso.authpolicy.internal.application;

import com.example.sso.authpolicy.factor.AuthFactor;
import com.example.sso.authpolicy.policy.AuthPolicyView;
import com.example.sso.authpolicy.policy.LoginAssignment;
import java.util.List;
import java.util.UUID;

/** Admin view of an authentication policy. steps = ordered list of allowed-factor choices. */
public record PolicyView(String id, String name, int priority, boolean enabled, boolean appliesToLogin,
                         boolean allowEnrollmentAtLogin,
                         List<List<String>> steps, List<String> assignedUserIds, List<String> assignedRoleIds,
                         int stepUpFreshnessMinutes) {

    /** Projects a policy plus its login scope (from the policy_binding matrix) to the admin view. */
    public static PolicyView of(AuthPolicyView policy, LoginAssignment login) {
        List<List<String>> steps = policy.getSteps().stream()
                .map(step -> step.getAllowedFactors().stream().map(AuthFactor::name).sorted().toList())
                .toList();
        return new PolicyView(policy.getId().toString(), policy.getName(), policy.getPriority(), policy.isEnabled(),
                login.appliesToLogin(), policy.isAllowEnrollmentAtLogin(), steps,
                login.userIds().stream().map(UUID::toString).sorted().toList(),
                login.roleIds().stream().map(UUID::toString).sorted().toList(),
                policy.getStepUpFreshnessMinutes());
    }
}
