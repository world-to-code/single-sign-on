package com.example.sso.authpolicy.internal.application;

import com.example.sso.authpolicy.AuthFactor;
import com.example.sso.authpolicy.AuthPolicyStepView;
import java.util.Set;
import lombok.Getter;

/**
 * Detached {@link AuthPolicyStepView} carrying one step's allowed factors. Built by
 * {@link AuthPolicyProjection} so a create/update result reflects the just-written factors without
 * touching a (stale) JPA collection on the freshly-persisted entity.
 */
@Getter
final class AuthPolicyStepProjection implements AuthPolicyStepView {

    private final Set<AuthFactor> allowedFactors;

    AuthPolicyStepProjection(Set<AuthFactor> allowedFactors) {
        this.allowedFactors = Set.copyOf(allowedFactors);
    }
}
