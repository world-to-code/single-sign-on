package com.example.sso.authpolicy.factor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AuthFactor#isKnownAuthority} decides what counts as a factor when the token customizer computes
 * {@code acr}, so its job is to recognize THIS IdP's factors and nothing else.
 *
 * <p>The {@code FACTOR_} prefix is not a safe test for that: Spring Security defines its own authorities in
 * the same namespace ({@code FACTOR_AUTHORIZATION_CODE}, {@code FACTOR_SAML_RESPONSE}, {@code FACTOR_BEARER},
 * …) and mints them from configurers a future refactor could wire. Counting one of those would push a
 * single-factor login to {@code acr=mfa} — past the admin elevation gate — while naming nothing in
 * {@code amr}.
 */
class AuthFactorTest {

    @Test
    void everyDeclaredFactorIsKnown() {
        for (AuthFactor factor : AuthFactor.values()) {
            assertThat(AuthFactor.isKnownAuthority(factor.authority()))
                    .as("%s must be recognized by its own authority", factor)
                    .isTrue();
        }
    }

    /** The exact strings Spring Security owns in this namespace, which this IdP never mints itself. */
    @Test
    void springSecuritysOwnFactorAuthoritiesAreNotKnownHere() {
        assertThat(AuthFactor.isKnownAuthority("FACTOR_AUTHORIZATION_CODE")).isFalse();
        assertThat(AuthFactor.isKnownAuthority("FACTOR_SAML_RESPONSE")).isFalse();
        assertThat(AuthFactor.isKnownAuthority("FACTOR_BEARER")).isFalse();
        assertThat(AuthFactor.isKnownAuthority("FACTOR_OTT")).isFalse();
        assertThat(AuthFactor.isKnownAuthority("FACTOR_X509")).isFalse();
        assertThat(AuthFactor.isKnownAuthority("FACTOR_CAS")).isFalse();
    }

    /**
     * The two that COINCIDE with Spring's own names stay known — they are this IdP's factors, minted by the
     * same providers, and refusing them would under-count a real password or passkey login.
     */
    @Test
    void theTwoAuthoritiesSharedWithSpringSecurityStayKnown() {
        assertThat(AuthFactor.isKnownAuthority("FACTOR_PASSWORD")).isTrue();
        assertThat(AuthFactor.isKnownAuthority("FACTOR_WEBAUTHN")).isTrue();
    }

    @Test
    void anythingOutsideTheNamespaceIsNotKnown() {
        assertThat(AuthFactor.isKnownAuthority("ROLE_ADMIN")).isFalse();
        assertThat(AuthFactor.isKnownAuthority("MFA_COMPLETE")).isFalse();
        assertThat(AuthFactor.isKnownAuthority("AUTH_FEDERATED")).isFalse();
        assertThat(AuthFactor.isKnownAuthority(null)).isFalse();
    }
}
