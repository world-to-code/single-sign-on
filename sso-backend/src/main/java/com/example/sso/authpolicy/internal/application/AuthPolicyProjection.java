package com.example.sso.authpolicy.internal.application;

import com.example.sso.authpolicy.factor.AuthFactor;
import com.example.sso.authpolicy.policy.AuthPolicyStepView;
import com.example.sso.authpolicy.policy.AuthPolicyView;
import com.example.sso.authpolicy.internal.domain.AuthPolicy;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * A detached {@link AuthPolicyView} built from a just-persisted policy plus the steps the admin service wrote
 * explicitly. Returned by create/update so the projection reflects the write directly — the freshly-persisted
 * entity's own {@code steps} collection is the empty in-memory instance Hibernate never re-loads within the
 * writing session, so reading it would project nothing. Login scope is not on the policy; the presentation
 * adapter reads it from the {@code policy_binding} matrix.
 */
@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
final class AuthPolicyProjection implements AuthPolicyView {

    private final UUID id;
    private final String name;
    private final int priority;
    private final boolean enabled;
    private final boolean allowEnrollmentAtLogin;
    private final int stepUpFreshnessMinutes;
    private final List<AuthPolicyStepView> steps;

    /** Projects the persisted scalar state (read off the entity) plus the written steps. */
    static AuthPolicyView of(AuthPolicy policy, List<? extends Set<AuthFactor>> steps) {
        List<AuthPolicyStepView> stepViews = steps.stream()
                .<AuthPolicyStepView>map(AuthPolicyStepProjection::new).toList();
        return new AuthPolicyProjection(policy.getId(), policy.getName(), policy.getPriority(),
                policy.isEnabled(), policy.isAllowEnrollmentAtLogin(),
                policy.getStepUpFreshnessMinutes(), stepViews);
    }
}
