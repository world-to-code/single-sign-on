package com.example.sso.auth.internal.login.application;

import com.example.sso.auth.internal.factor.application.FactorHandlers;
import com.example.sso.authpolicy.factor.AuthFactor;
import com.example.sso.user.account.AccountHoldService;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What counts as paying a hold's price.
 *
 * <p>Worth its own test because the rule is stated as a SUBTRACTION — everything that is not the password —
 * and a subtraction has consequences nobody chose one at a time. The passwordless case below is the one that
 * would otherwise be discovered rather than decided.
 */
class AccountHoldLoginGateTest {

    private final AccountHoldLoginGate gate = new AccountHoldLoginGate(
            Mockito.mock(AccountHoldService.class), Mockito.mock(FactorHandlers.class));

    @Test
    void thePasswordAloneDoesNotPay() {
        assertThat(gate.paidBy(Set.of(AuthFactor.PASSWORD.authority()))).isFalse();
    }

    @Test
    void anyOtherProvenFactorPays() {
        assertThat(gate.paidBy(Set.of(AuthFactor.PASSWORD.authority(), AuthFactor.TOTP.authority()))).isTrue();
    }

    /**
     * A passwordless passkey sign-in pays on its own, and that is the intended reading rather than an
     * oversight: a hold distrusts the PASSWORD, and a passkey is neither the credential under suspicion nor
     * something the holder of a stolen password can produce. Demanding a further factor of somebody who never
     * used a password would be asking them to answer for a credential they did not present.
     */
    @Test
    void aPasskeySignInPaysWithoutAPasswordHavingBeenUsedAtAll() {
        assertThat(gate.paidBy(Set.of(AuthFactor.FIDO2.authority()))).isTrue();
    }

    /** Authorities that are not this IdP's factors are not factors. Roles and scopes must never pay a hold. */
    @Test
    void aNonFactorAuthorityDoesNotPay() {
        assertThat(gate.paidBy(Set.of("ROLE_ADMIN", "user:read", "FACTOR_BEARER"))).isFalse();
    }
}
