package com.example.sso.config.internal;

import com.example.sso.authpolicy.factor.Factors;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `amr` is a claim other systems make trust decisions on — an app may decline a single factor, or insist on a
 * hardware key — so a wrong value misinforms them silently rather than failing. Each method maps on its own
 * axis, and a federated session must never claim this IdP checked a password.
 */
class AuthenticationMethodReferencesTest {

    @Test
    void aPasswordLoginReportsPwd() {
        assertThat(AuthenticationMethodReferences.of(Set.of(Factors.PASSWORD), 1)).containsExactly("pwd");
    }

    /**
     * A federated login satisfies the PRIMARY factor with FACTOR_PASSWORD, but this IdP never saw a password —
     * the upstream authenticated the user. Claiming `pwd` would assert a credential check that did not happen.
     */
    @Test
    void aFederatedLoginReportsFedAndNeverPwd() {
        Set<String> authorities = Set.of(Factors.PASSWORD, Factors.FEDERATED);

        assertThat(AuthenticationMethodReferences.of(authorities, 1))
                .containsExactly("fed")
                .doesNotContain("pwd");
    }

    @Test
    void aFederatedLoginWithASecondFactorReportsBothAndMfa() {
        Set<String> authorities = Set.of(Factors.PASSWORD, Factors.FEDERATED, Factors.TOTP);

        assertThat(AuthenticationMethodReferences.of(authorities, 2))
                .containsExactlyInAnyOrder("fed", "otp", "mfa")
                .doesNotContain("pwd");
    }

    @Test
    void totpAndEmailBothReportOtpButOnlyOnce() {
        assertThat(AuthenticationMethodReferences.of(Set.of(Factors.TOTP, Factors.EMAIL), 2))
                .containsExactlyInAnyOrder("otp", "mfa");
    }

    @Test
    void aPasskeyReportsHardwareBacked() {
        assertThat(AuthenticationMethodReferences.of(Set.of(Factors.FIDO2), 1)).containsExactly("hwk");
    }

    /** `mfa` asserts two INDEPENDENT factors; one factor must never claim it. */
    @Test
    void oneFactorNeverClaimsMfa() {
        assertThat(AuthenticationMethodReferences.of(Set.of(Factors.PASSWORD), 1)).doesNotContain("mfa");
        assertThat(AuthenticationMethodReferences.of(Set.of(Factors.PASSWORD), 2)).contains("mfa");
    }

    /** The FEDERATED marker is deliberately not FACTOR_-prefixed, so it must not be reported as a method. */
    @Test
    void aSessionWithNoRecognisedMethodReportsNothing() {
        assertThat(AuthenticationMethodReferences.of(Set.of(Factors.FEDERATED), 0)).containsExactly("fed");
        assertThat(AuthenticationMethodReferences.of(Set.of(), 0)).isEmpty();
    }
}
