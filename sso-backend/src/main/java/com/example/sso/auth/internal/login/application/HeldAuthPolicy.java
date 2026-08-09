package com.example.sso.auth.internal.login.application;

import com.example.sso.authpolicy.factor.AuthFactor;
import com.example.sso.authpolicy.policy.AuthPolicyStepView;
import com.example.sso.authpolicy.policy.AuthPolicyView;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The account's own authentication policy, as a hold makes it: one more step demanding a factor that is not
 * the password, and no enrolling your way through it.
 *
 * <p>A decorator rather than an edit, because the tenant's policy is not what changed — a hold is a fact about
 * this account for the next few hours, and writing it into the policy would outlive the suspicion and apply to
 * everyone the policy covers.
 *
 * <p><b>Enrolment is off while held, unconditionally.</b> Enrolment-at-login lets somebody holding only the
 * password register a brand-new factor and satisfy the very step meant to test whether they are that person.
 * That is a bypass the hold cannot tolerate, and it is why this decorator is applied even when there is no
 * second factor to append: the account is then simply unable to finish, which is the correct answer.
 *
 * <p>The extra step is skipped when the account has nothing to prove with, because a step allowing no factors
 * is one nobody can ever satisfy — the sign-in would sit on a screen offering no way forward instead of being
 * told plainly that the account is held.
 */
class HeldAuthPolicy implements AuthPolicyView {

    private final AuthPolicyView delegate;
    private final List<AuthPolicyStepView> steps;

    HeldAuthPolicy(AuthPolicyView delegate, Set<AuthFactor> availableSecondFactors) {
        this.delegate = delegate;
        this.steps = new ArrayList<>(delegate.getSteps());
        if (!availableSecondFactors.isEmpty()) {
            this.steps.add(new SecondFactorStep(availableSecondFactors));
        }
    }

    @Override
    public UUID getId() {
        return delegate.getId();
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public int getPriority() {
        return delegate.getPriority();
    }

    @Override
    public boolean isEnabled() {
        return delegate.isEnabled();
    }

    @Override
    public boolean isAllowEnrollmentAtLogin() {
        return false;
    }

    @Override
    public int getStepUpFreshnessMinutes() {
        return delegate.getStepUpFreshnessMinutes();
    }

    @Override
    public List<? extends AuthPolicyStepView> getSteps() {
        return steps;
    }
}
