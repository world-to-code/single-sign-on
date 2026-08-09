package com.example.sso.auth.internal.factor.application;

import com.example.sso.authpolicy.factor.AuthFactor;
import com.example.sso.user.account.UserService;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What counts as a factor other than the password.
 *
 * <p>Worth its own test because the rule is stated as a SUBTRACTION — everything that is not the password —
 * and a subtraction has consequences nobody chose one at a time. The passwordless case below is the one that
 * would otherwise be discovered rather than decided.
 *
 * <p>This is also the answer an account hold's price is paid in, and the one that decides whether a machine
 * may place a hold at all. Both read it from here, so this is where it is pinned.
 */
class EnrolledSecondFactorsTest {

    private final EnrolledSecondFactors secondFactors = new EnrolledSecondFactors(
            Mockito.mock(FactorHandlers.class), Mockito.mock(UserService.class));

    @Test
    void thePasswordAloneIsNotOne() {
        assertThat(secondFactors.provenIn(Set.of(AuthFactor.PASSWORD.authority()))).isFalse();
    }

    @Test
    void anyOtherProvenFactorIs() {
        assertThat(secondFactors.provenIn(
                Set.of(AuthFactor.PASSWORD.authority(), AuthFactor.TOTP.authority()))).isTrue();
    }

    /**
     * A passwordless passkey sign-in counts on its own, and that is the intended reading rather than an
     * oversight: a hold distrusts the PASSWORD, and a passkey is neither the credential under suspicion nor
     * something the holder of a stolen password can produce. Demanding a further factor of somebody who never
     * used a password would be asking them to answer for a credential they did not present.
     */
    @Test
    void aPasskeyCountsWithoutAPasswordHavingBeenUsedAtAll() {
        assertThat(secondFactors.provenIn(Set.of(AuthFactor.FIDO2.authority()))).isTrue();
    }

    /** Authorities that are not this IdP's factors are not factors. Roles and scopes must never count. */
    @Test
    void aNonFactorAuthorityIsNotOne() {
        assertThat(secondFactors.provenIn(Set.of("ROLE_ADMIN", "user:read", "FACTOR_BEARER"))).isFalse();
    }
}
