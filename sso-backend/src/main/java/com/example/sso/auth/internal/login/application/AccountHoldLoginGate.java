package com.example.sso.auth.internal.login.application;

import com.example.sso.auth.internal.factor.application.EnrolledSecondFactors;
import com.example.sso.authpolicy.policy.AuthPolicyView;
import com.example.sso.user.account.AccountHoldService;
import com.example.sso.user.account.UserAccount;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * What a live hold costs a sign-in.
 *
 * <p>The {@code user} module owns the FACT that an account is held; this owns the PRICE. Keeping them apart is
 * what lets a response system place a hold without knowing anything about factors, and lets the price change
 * — a different factor, a shorter freshness — without touching the thing that records the suspicion.
 *
 * <p>The price is a second factor, and the fail-closed branch is the whole point: an account with nothing to
 * prove but its password cannot pay, because the password is the credential under suspicion. Letting it
 * through on a re-typed password would be a control that looks like one and stops nobody.
 *
 * <p>WHICH factors an account can prove is {@link EnrolledSecondFactors}' answer, not this class's. The same
 * answer decides whether a machine may place a hold at all, and two versions of it would eventually disagree
 * about whether an account can pay — where the direction that disagrees silently is the permissive one.
 */
@Component
@RequiredArgsConstructor
class AccountHoldLoginGate {

    private final AccountHoldService holds;
    private final EnrolledSecondFactors secondFactors;

    /** Whether a hold is in force on this account at this moment. */
    boolean inEffect(UUID userId) {
        return holds.holdInEffect(userId).isPresent();
    }

    /** The policy this sign-in must satisfy while the account is held. */
    AuthPolicyView tighten(AuthPolicyView policy, UserAccount user) {
        return new HeldAuthPolicy(policy, secondFactors.availableFor(user));
    }

    /**
     * Whether the session has already proven a factor other than the password — the hold's price, paid.
     *
     * <p>Read only once the policy is otherwise complete: reaching that point still owing it means the
     * account had no second factor to offer, and the sign-in is refused rather than completed.
     */
    boolean paidBy(Set<String> grantedAuthorities) {
        return secondFactors.provenIn(grantedAuthorities);
    }
}
