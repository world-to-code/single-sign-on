package com.example.sso.auth.internal.factor.application;

import com.example.sso.auth.factor.SecondFactors;
import com.example.sso.authpolicy.factor.AuthFactor;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The one place that answers "what can this account prove besides its password".
 *
 * <p>Everything that is not the password, derived rather than listed, so a factor added to the enum counts
 * as a second factor the day it exists — the failure of a hand-written list here is silent and permissive.
 * Availability is the handler's own {@code isEnrolled}, which is the same condition it re-checks before
 * issuing a challenge, so this cannot promise a factor the challenge would then refuse.
 */
@Service
@RequiredArgsConstructor
public class EnrolledSecondFactors implements SecondFactors {

    private static final Set<AuthFactor> SECOND_FACTORS = EnumSet.complementOf(EnumSet.of(AuthFactor.PASSWORD));

    private final FactorHandlers factorHandlers;
    private final UserService users;

    @Override
    public boolean available(UUID userId) {
        return users.findById(userId).map(user -> !availableFor(user).isEmpty()).orElse(false);
    }

    /** The factors themselves, for the caller that has to offer a choice rather than just count them. */
    public Set<AuthFactor> availableFor(UserAccount user) {
        Set<AuthFactor> available = new LinkedHashSet<>();
        for (AuthFactor factor : SECOND_FACTORS) {
            if (factorHandlers.isEnrolled(factor, user)) {
                available.add(factor);
            }
        }
        return available;
    }

    /** Whether the session has already proven one — a hold's price, paid. */
    public boolean provenIn(Set<String> grantedAuthorities) {
        return SECOND_FACTORS.stream().anyMatch(factor -> grantedAuthorities.contains(factor.authority()));
    }
}
