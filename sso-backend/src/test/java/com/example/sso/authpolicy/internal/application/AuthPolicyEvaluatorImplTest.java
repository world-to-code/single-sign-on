package com.example.sso.authpolicy.internal.application;

import com.example.sso.authpolicy.AuthFactor;
import com.example.sso.authpolicy.AuthPolicyStepView;
import com.example.sso.authpolicy.AuthPolicyView;
import com.example.sso.authpolicy.Factors;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link AuthPolicyEvaluatorImpl}: purely evaluates an ordered policy against the
 * factors a session has already satisfied. It works only on the public view projections, so the
 * policy/steps are mocked (the entity's persistence is covered elsewhere).
 */
class AuthPolicyEvaluatorImplTest {

    private final AuthPolicyEvaluatorImpl evaluator = new AuthPolicyEvaluatorImpl();

    private AuthPolicyStepView step(Set<AuthFactor> factors) {
        AuthPolicyStepView step = mock(AuthPolicyStepView.class);
        when(step.getAllowedFactors()).thenReturn(factors);
        return step;
    }

    private AuthPolicyView policy(AuthPolicyStepView... steps) {
        AuthPolicyView policy = mock(AuthPolicyView.class);
        doReturn(List.of(steps)).when(policy).getSteps();
        return policy;
    }

    private AuthPolicyView passwordThenTotp() {
        return policy(step(Set.of(AuthFactor.PASSWORD)), step(Set.of(AuthFactor.TOTP)));
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
        AuthPolicyView policy = policy(step(Set.of(AuthFactor.TOTP, AuthFactor.EMAIL)));

        assertThat(evaluator.isSatisfied(policy, Set.of(Factors.EMAIL))).isTrue();
    }
}
