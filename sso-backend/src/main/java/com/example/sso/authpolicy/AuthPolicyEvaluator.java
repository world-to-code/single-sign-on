package com.example.sso.authpolicy;

import java.util.Optional;
import java.util.Set;

/**
 * Evaluates an authentication policy against the factors a session has already satisfied. Operates on
 * the public {@link AuthPolicyView}/{@link AuthPolicyStepView} projections. The implementation stays
 * module-internal.
 */
public interface AuthPolicyEvaluator {

    /** The first step whose allowed factors are not yet satisfied, or empty if the policy is complete. */
    Optional<AuthPolicyStepView> currentStep(AuthPolicyView policy, Set<String> grantedAuthorities);

    boolean isSatisfied(AuthPolicyView policy, Set<String> grantedAuthorities);
}
