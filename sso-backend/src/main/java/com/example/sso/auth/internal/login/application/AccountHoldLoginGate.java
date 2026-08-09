package com.example.sso.auth.internal.login.application;

import com.example.sso.auth.internal.factor.application.FactorHandlers;
import com.example.sso.authpolicy.factor.AuthFactor;
import com.example.sso.authpolicy.policy.AuthPolicyView;
import com.example.sso.user.account.AccountHoldService;
import com.example.sso.user.account.UserAccount;
import java.util.EnumSet;
import java.util.LinkedHashSet;
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
 */
@Component
@RequiredArgsConstructor
class AccountHoldLoginGate {

    /**
     * Everything that is not the password. Derived rather than listed, so a factor added to the enum tomorrow
     * counts as a second factor by default — the failure of a hand-written list is silent and permissive.
     */
    private static final Set<AuthFactor> SECOND_FACTORS = EnumSet.complementOf(EnumSet.of(AuthFactor.PASSWORD));

    private final AccountHoldService holds;
    private final FactorHandlers factorHandlers;

    /** Whether a hold is in force on this account at this moment. */
    boolean inEffect(UUID userId) {
        return holds.holdInEffect(userId).isPresent();
    }

    /** The policy this sign-in must satisfy while the account is held. */
    AuthPolicyView tighten(AuthPolicyView policy, UserAccount user) {
        return new HeldAuthPolicy(policy, availableSecondFactors(user));
    }

    /**
     * Whether the session has already proven a factor other than the password — the hold's price, paid.
     * Read only once the policy is otherwise complete: reaching that point still owing it means the account
     * had no second factor to offer, and the sign-in is refused rather than completed.
     */
    boolean paidBy(Set<String> grantedAuthorities) {
        return SECOND_FACTORS.stream().anyMatch(factor -> grantedAuthorities.contains(factor.authority()));
    }

    /**
     * The factors this account could actually complete right now — enrolled, and with the contact it needs
     * already proven. Deliberately not "the factors the policy mentions": a hold asks what this person can
     * demonstrate, which is a property of the account and not of the tenant's configuration.
     */
    private Set<AuthFactor> availableSecondFactors(UserAccount user) {
        Set<AuthFactor> available = new LinkedHashSet<>();
        for (AuthFactor factor : SECOND_FACTORS) {
            if (factorHandlers.isEnrolled(factor, user)) {
                available.add(factor);
            }
        }
        return available;
    }
}
