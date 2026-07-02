package com.example.sso.authpolicy.internal.application;

import com.example.sso.authpolicy.AuthFactor;
import com.example.sso.authpolicy.AuthPolicyStepView;
import com.example.sso.authpolicy.Factors;
import com.example.sso.authpolicy.internal.domain.AuthPolicy;
import com.example.sso.authpolicy.internal.domain.AuthPolicyStep;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link AuthPolicyEvaluatorImpl}: purely evaluates an ordered policy against the
 * factors a session has already satisfied. No collaborators — asserts on the returned step/flag.
 */
class AuthPolicyEvaluatorImplTest {

    private final AuthPolicyEvaluatorImpl evaluator = new AuthPolicyEvaluatorImpl();

    private AuthPolicy passwordThenTotp() {
        AuthPolicy policy = new AuthPolicy("MFA", 1);
        policy.addStep(new AuthPolicyStep(1, Set.of(AuthFactor.PASSWORD)));
        policy.addStep(new AuthPolicyStep(2, Set.of(AuthFactor.TOTP)));
        return policy;
    }

    @Test
    void currentStepIsTheFirstStepWhenNothingIsSatisfied() {
        Optional<AuthPolicyStepView> step = evaluator.currentStep(passwordThenTotp(), Set.of());

        assertThat(step).isPresent();
        assertThat(step.get().getAllowedFactors()).containsExactly(AuthFactor.PASSWORD);
    }

    @Test
    void currentStepAdvancesPastAlreadySatisfiedSteps() {
        Optional<AuthPolicyStepView> step =
                evaluator.currentStep(passwordThenTotp(), Set.of(Factors.PASSWORD));

        assertThat(step).isPresent();
        assertThat(step.get().getAllowedFactors()).containsExactly(AuthFactor.TOTP);
    }

    @Test
    void currentStepIsEmptyWhenAllStepsAreSatisfied() {
        Set<String> granted = Set.of(Factors.PASSWORD, Factors.TOTP);

        assertThat(evaluator.currentStep(passwordThenTotp(), granted)).isEmpty();
        assertThat(evaluator.isSatisfied(passwordThenTotp(), granted)).isTrue();
    }

    @Test
    void isSatisfiedIsFalseWhileAStepRemains() {
        assertThat(evaluator.isSatisfied(passwordThenTotp(), Set.of(Factors.PASSWORD))).isFalse();
    }

    @Test
    void aStepIsSatisfiedByAnyOneOfItsAllowedFactors() {
        AuthPolicy policy = new AuthPolicy("choice", 1);
        policy.addStep(new AuthPolicyStep(1, Set.of(AuthFactor.TOTP, AuthFactor.EMAIL)));

        assertThat(evaluator.isSatisfied(policy, Set.of(Factors.EMAIL))).isTrue();
    }
}
