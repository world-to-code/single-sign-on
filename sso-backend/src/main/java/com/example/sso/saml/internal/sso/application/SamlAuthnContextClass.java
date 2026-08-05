package com.example.sso.saml.internal.sso.application;

import com.example.sso.authpolicy.factor.AuthFactor;
import com.example.sso.authpolicy.factor.Factors;
import java.util.Set;
import org.opensaml.saml.saml2.core.AuthnContext;

/**
 * The SAML {@code <AuthnContextClassRef>} to assert for a set of satisfied factors.
 *
 * <p>SAML 2.0 Core §2.7.2.2 makes this a truth claim about "the context used by the authenticating
 * authority", and {@code saml-authn-context-2.0-os} §3.4 defines {@code PasswordProtectedTransport} as the
 * principal having authenticated <b>by presenting a password over a protected session</b>. An SP deciding
 * whether to accept a session acts on it.
 *
 * <p>Every assertion used to hardcode that class. A passwordless passkey login — a per-org feature that
 * ships — told every SAML SP a password had been typed, and so did a federated login where this IdP never
 * saw a credential. The OIDC side had already been corrected to stop reporting a password it never checked;
 * this is the same clause in the dialect that had not caught up.
 *
 * <p><b>Only two values, deliberately.</b> {@code PasswordProtectedTransport} when a password was actually
 * verified here, and {@code unspecified} otherwise. {@code unspecified} is a registered class meaning the
 * authentication was performed by unspecified means — it says less than an SP might want, but everything it
 * says is true, and an under-specified claim is recoverable in a way a false one is not.
 *
 * <p><b>Why no multi-factor class.</b> The obvious addition is the REFEDS MFA profile for two-or-more
 * factors, and it is rejected on the reasoning this class exists to apply: REFEDS MFA requires factors from
 * independent categories, while what is counted here is distinct factor AUTHORITIES. Two codes delivered to
 * two contacts count as two here and would not satisfy that profile, so asserting it would be the same shape
 * of overclaim in a new place. Password plus a second factor stays {@code PasswordProtectedTransport},
 * which remains true. If categories are ever modelled, this is the one place that has to change.
 */
final class SamlAuthnContextClass {

    private SamlAuthnContextClass() {
    }

    /**
     * @param factors the granted authorities of the authenticated session, filtered to this IdP's own factors
     *                by the caller — a foreign {@code FACTOR_} authority must not become a password claim
     */
    static String of(Set<String> factors) {
        // A federated session carries FACTOR_PASSWORD as its primary marker although nothing was verified
        // here. The upstream authenticated the user, so this IdP cannot characterize how, and says so.
        boolean federated = factors.contains(Factors.FEDERATED);
        boolean passwordVerifiedHere = factors.contains(AuthFactor.PASSWORD.authority()) && !federated;
        return passwordVerifiedHere ? AuthnContext.PPT_AUTHN_CTX : AuthnContext.UNSPECIFIED_AUTHN_CTX;
    }
}
