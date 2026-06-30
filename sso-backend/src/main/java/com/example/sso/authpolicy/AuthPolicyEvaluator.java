package com.example.sso.authpolicy;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * Evaluates an authentication policy against the factors a session has already satisfied.
 */
@Component
public class AuthPolicyEvaluator {

    /** The first step whose allowed factors are not yet satisfied, or empty if the policy is complete. */
    public Optional<AuthPolicyStep> currentStep(AuthPolicy policy, Set<String> grantedAuthorities) {
        return policy.getSteps().stream()
                .filter(step -> step.getAllowedFactors().stream()
                        .noneMatch(factor -> grantedAuthorities.contains(factor.authority())))
                .findFirst();
    }

    public boolean isSatisfied(AuthPolicy policy, Set<String> grantedAuthorities) {
        return currentStep(policy, grantedAuthorities).isEmpty();
    }
}
