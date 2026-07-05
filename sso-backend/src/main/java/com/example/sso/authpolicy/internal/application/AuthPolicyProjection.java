package com.example.sso.authpolicy.internal.application;

import com.example.sso.authpolicy.AuthFactor;
import com.example.sso.authpolicy.AuthPolicyStepView;
import com.example.sso.authpolicy.AuthPolicyView;
import com.example.sso.authpolicy.internal.domain.AuthPolicy;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * A detached {@link AuthPolicyView} built from a just-persisted policy plus the steps and assignments the
 * admin service wrote explicitly. Returned by create/update so the projection reflects the write directly
 * — the freshly-persisted entity's own {@code steps}/assignment collections are the empty in-memory
 * instances Hibernate never re-loads within the writing session, so reading them would project nothing.
 */
@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
final class AuthPolicyProjection implements AuthPolicyView {

    private final UUID id;
    private final String name;
    private final int priority;
    private final boolean enabled;
    private final boolean appliesToLogin;
    private final boolean allowEnrollmentAtLogin;
    private final int stepUpFreshnessMinutes;
    private final List<AuthPolicyStepView> steps;
    private final Set<UUID> assignedUserIds;
    private final Set<UUID> assignedRoleIds;

    /** Projects the persisted scalar state (read off the entity) plus the written steps/assignments. */
    static AuthPolicyView of(AuthPolicy policy, List<? extends Set<AuthFactor>> steps,
                             Set<UUID> userIds, Set<UUID> roleIds) {
        List<AuthPolicyStepView> stepViews = steps.stream()
                .<AuthPolicyStepView>map(AuthPolicyStepProjection::new).toList();
        return new AuthPolicyProjection(policy.getId(), policy.getName(), policy.getPriority(),
                policy.isEnabled(), policy.isAppliesToLogin(), policy.isAllowEnrollmentAtLogin(),
                policy.getStepUpFreshnessMinutes(), stepViews, Set.copyOf(userIds), Set.copyOf(roleIds));
    }
}
