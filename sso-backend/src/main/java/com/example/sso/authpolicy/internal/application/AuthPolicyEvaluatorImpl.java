package com.example.sso.authpolicy.internal.application;

import com.example.sso.authpolicy.AuthPolicyEvaluator;
import com.example.sso.authpolicy.AuthPolicyStepView;
import com.example.sso.authpolicy.AuthPolicyView;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * Default {@link AuthPolicyEvaluator}. Evaluates an authentication policy against the factors a
 * session has already satisfied, working purely on the public view projections.
 */
@Component
public class AuthPolicyEvaluatorImpl implements AuthPolicyEvaluator {

    @Override
    public Optional<AuthPolicyStepView> currentStep(AuthPolicyView policy, Set<String> grantedAuthorities) {
        return policy.getSteps().stream()
                .filter(step -> step.getAllowedFactors().stream()
                        .noneMatch(factor -> grantedAuthorities.contains(factor.authority())))
                .findFirst()
                .map(AuthPolicyStepView.class::cast);
    }

    @Override
    public boolean isSatisfied(AuthPolicyView policy, Set<String> grantedAuthorities) {
        return currentStep(policy, grantedAuthorities).isEmpty();
    }
}
