package com.example.sso.saml.internal.sso.application;

import com.example.sso.authpolicy.factor.AuthFactor;
import com.example.sso.authpolicy.factor.Factors;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.opensaml.saml.saml2.core.AuthnContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code <AuthnContext>} a SAML assertion carries is a MUST-grade truth claim: SAML 2.0 Core §2.7.2.2
 * says the class describes "the context used by the authenticating authority", and an SP making a strength
 * decision acts on it.
 *
 * <p>Every assertion used to hardcode {@code PasswordProtectedTransport} — "the principal authenticated by
 * presenting a password over a protected session" — regardless of what actually happened. A passwordless
 * passkey login, a shipped per-org feature, told every SAML SP that a password was typed. So did a federated
 * login where this IdP never saw a credential. The OIDC side had already been fixed to stop reporting a
 * password it never checked; this is the same clause in the other dialect.
 */
class SamlAuthnContextClassTest {

    @Test
    void aPasswordLoginIsPasswordProtectedTransport() {
        assertThat(SamlAuthnContextClass.of(Set.of(Factors.PASSWORD)))
                .isEqualTo(AuthnContext.PPT_AUTHN_CTX);
    }

    /**
     * The defect. Passwordless passkey sign-in is a per-org feature that ships today, so this assertion was
     * false on the wire for every tenant that enabled it.
     */
    @Test
    void aPasswordlessPasskeyLoginIsNotPasswordProtectedTransport() {
        String uri = SamlAuthnContextClass.of(Set.of(Factors.FIDO2));

        assertThat(uri).isNotEqualTo(AuthnContext.PPT_AUTHN_CTX);
        assertThat(uri).isEqualTo(AuthnContext.UNSPECIFIED_AUTHN_CTX);
    }

    /**
     * A federated login carries FACTOR_PASSWORD as its primary marker even though no password was checked
     * here — the same trap that made the OIDC {@code amr} report {@code pwd}. The upstream authenticated the
     * user, so this IdP cannot characterize how.
     */
    @Test
    void aFederatedLoginIsUnspecifiedEvenThoughItCarriesThePasswordMarker() {
        assertThat(SamlAuthnContextClass.of(Set.of(Factors.PASSWORD, Factors.FEDERATED)))
                .isEqualTo(AuthnContext.UNSPECIFIED_AUTHN_CTX);
    }

    /** Every single non-password factor is unspecified rather than mischaracterized as a password. */
    @Test
    void noSingleNonPasswordFactorClaimsAPassword() {
        for (AuthFactor factor : AuthFactor.values()) {
            if (factor == AuthFactor.PASSWORD) {
                continue;
            }
            assertThat(SamlAuthnContextClass.of(Set.of(factor.authority())))
                    .as("%s must not be asserted as a password", factor)
                    .isEqualTo(AuthnContext.UNSPECIFIED_AUTHN_CTX);
        }
    }

    /**
     * Multi-factor is deliberately NOT asserted as a stronger class. See the production Javadoc: the count
     * here is of distinct factor authorities, not of independent NIST categories, so claiming a profile that
     * requires categories (REFEDS MFA) would be the same kind of overclaim this change exists to remove.
     * Password plus a second factor is still, truthfully, a password over a protected transport.
     */
    @Test
    void passwordPlusASecondFactorStaysPasswordProtectedTransport() {
        assertThat(SamlAuthnContextClass.of(Set.of(Factors.PASSWORD, Factors.TOTP)))
                .isEqualTo(AuthnContext.PPT_AUTHN_CTX);
    }

    /** A session carrying no recognized factor asserts nothing about how it was established. */
    @Test
    void noRecognizedFactorIsUnspecified() {
        assertThat(SamlAuthnContextClass.of(Set.of())).isEqualTo(AuthnContext.UNSPECIFIED_AUTHN_CTX);
        assertThat(SamlAuthnContextClass.of(Set.of("ROLE_USER"))).isEqualTo(AuthnContext.UNSPECIFIED_AUTHN_CTX);
    }

    /** Spring Security's own FACTOR_ authorities must not be read as this IdP having verified a password. */
    @Test
    void aForeignFactorAuthorityDoesNotBecomeAPasswordClaim() {
        assertThat(SamlAuthnContextClass.of(Set.of("FACTOR_AUTHORIZATION_CODE")))
                .isEqualTo(AuthnContext.UNSPECIFIED_AUTHN_CTX);
    }
}
