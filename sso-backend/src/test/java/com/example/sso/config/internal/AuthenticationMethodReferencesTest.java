package com.example.sso.config.internal;

import com.example.sso.authpolicy.factor.AuthFactor;
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

    /**
     * RFC 8176 splits proof-of-possession by where the key lives: {@code hwk} is a HARDWARE-secured key,
     * {@code swk} a software-secured one. A device-bound passkey — a security key, or one in a TPM/secure
     * enclave that cannot leave it — is the first.
     */
    @Test
    void aDeviceBoundPasskeyReportsHardwareBacked() {
        assertThat(AuthenticationMethodReferences.of(Set.of(Factors.FIDO2), 1)).containsExactly("hwk");
    }

    /**
     * A SYNCED passkey is copied into the platform's account keychain by design, so the private key is not
     * bound to any one piece of hardware. Reporting it as {@code hwk} tells a relying party that gates a
     * high-assurance action on a physical key that it got one — this class's own reason for existing, and the
     * same overclaim already corrected for `pwd` on a federated login.
     */
    @Test
    void aSyncedPasskeyReportsSoftwareBackedInstead() {
        assertThat(AuthenticationMethodReferences.of(Set.of(Factors.FIDO2, Factors.SOFTWARE_BACKED_PASSKEY), 1))
                .containsExactly("swk");
    }

    /** The marker is not a factor: counting it would let one passkey claim two-factor authentication. */
    @Test
    void theSoftwareBackedMarkerNeverCountsTowardsMfa() {
        assertThat(AuthenticationMethodReferences.of(Set.of(Factors.FIDO2, Factors.SOFTWARE_BACKED_PASSKEY), 1))
                .doesNotContain("mfa");
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

    /**
     * RFC 8176 registers {@code sms} for "confirmation using SMS text message to the user at a registered
     * number", so a code texted to a phone has a value of its own and does not belong under {@code otp} —
     * whose registered definition names the HOTP/TOTP specifications specifically.
     */
    @Test
    void anSmsCodeReportsSms() {
        assertThat(AuthenticationMethodReferences.of(Set.of(Factors.SMS), 1)).containsExactly("sms");
    }

    /**
     * The defect this closes. factorCount already counted SMS, so the session claimed {@code mfa} while
     * naming a single method — a relying party that counts the methods it was given reached a different
     * answer than the one the claim asserted.
     */
    @Test
    void passwordAndSmsReportBothMethodsAndMfa() {
        assertThat(AuthenticationMethodReferences.of(Set.of(Factors.PASSWORD, Factors.SMS), 2))
                .containsExactlyInAnyOrder("pwd", "sms", "mfa");
    }

    /** SMS is its own axis: it neither absorbs nor is absorbed by the authenticator-app factor. */
    @Test
    void totpAndSmsReportBothSeparately() {
        assertThat(AuthenticationMethodReferences.of(Set.of(Factors.TOTP, Factors.SMS), 2))
                .containsExactlyInAnyOrder("otp", "sms", "mfa");
    }

    /** Email keeps folding into otp — RFC 8176 registers no email value — but that must not swallow sms. */
    @Test
    void emailAndSmsReportOtpAndSmsSeparately() {
        assertThat(AuthenticationMethodReferences.of(Set.of(Factors.EMAIL, Factors.SMS), 2))
                .containsExactlyInAnyOrder("otp", "sms", "mfa");
    }

    @Test
    void aFederatedLoginWithAnSmsSecondFactorReportsFedAndSmsButNeverPwd() {
        Set<String> authorities = Set.of(Factors.PASSWORD, Factors.FEDERATED, Factors.SMS);

        assertThat(AuthenticationMethodReferences.of(authorities, 2))
                .containsExactlyInAnyOrder("fed", "sms", "mfa")
                .doesNotContain("pwd");
    }

    /**
     * Every factor this IdP can clear names itself, which is what lets the token customizer keep tying
     * {@code acr} to a non-empty {@code amr}: the only session that reports no method is one that cleared no
     * factor, and asserting a strength level for that would claim a check nobody performed.
     *
     * <p>It iterates {@link AuthFactor#values()} rather than a literal list ON PURPOSE. With a hardcoded set,
     * adding a sixth constant leaves this green while re-creating the exact defect the SMS arm closed — the
     * new factor counted toward {@code factorCount} and named by nothing. A SPNEGO factor is already planned.
     */
    @Test
    void everyFactorReportsSomeMethod() {
        for (AuthFactor factor : AuthFactor.values()) {
            assertThat(AuthenticationMethodReferences.of(Set.of(factor.authority()), 1))
                    .as("factor %s must name itself in amr", factor)
                    .isNotEmpty();
        }
    }
}
