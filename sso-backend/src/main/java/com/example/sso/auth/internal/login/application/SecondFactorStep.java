package com.example.sso.auth.internal.login.application;

import com.example.sso.authpolicy.factor.AuthFactor;
import com.example.sso.authpolicy.policy.AuthPolicyStepView;
import java.util.Set;

/**
 * The step a hold adds: prove one factor that is not the password, chosen from the ones this account already
 * has. Satisfied by a factor the session proved EARLIER too — the evaluator only asks which authorities are
 * granted — so a policy that already demanded a second factor does not ask for it twice.
 */
record SecondFactorStep(Set<AuthFactor> allowedFactors) implements AuthPolicyStepView {

    @Override
    public Set<AuthFactor> getAllowedFactors() {
        return allowedFactors;
    }
}
