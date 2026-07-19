package com.example.sso.config.internal;

import com.example.sso.authpolicy.factor.Factors;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Maps the factor markers a session carries to RFC 8176 Authentication Method References, so a relying party
 * reading an id_token can tell HOW the user authenticated — and act on it (a bank app declining to accept a
 * single factor, an admin console demanding a hardware key).
 *
 * <p>Extracted from the token customizer so the mapping is directly testable: it is a claim other systems make
 * trust decisions on, and getting it wrong misinforms them rather than failing loudly.
 */
final class AuthenticationMethodReferences {

    /** Two factors is the threshold RFC 8176 `mfa` asserts. */
    private static final long MFA_THRESHOLD = 2;

    private AuthenticationMethodReferences() {
    }

    static List<String> of(Set<String> authorities, long factorCount) {
        List<String> amr = new ArrayList<>();
        // A federated login satisfies the PRIMARY factor with FACTOR_PASSWORD, but no password was checked
        // here — the upstream authenticated the user. Reporting `pwd` would tell a relying party that this IdP
        // verified a credential it never saw, so a federated session reports `fed` and never `pwd`.
        boolean federated = authorities.contains(Factors.FEDERATED);
        if (federated) {
            amr.add("fed");
        }
        if (authorities.contains(Factors.PASSWORD) && !federated) {
            amr.add("pwd");
        }
        if (authorities.contains(Factors.TOTP)) {
            amr.add("otp");
        }
        if (authorities.contains(Factors.EMAIL) && !amr.contains("otp")) {
            amr.add("otp");
        }
        if (authorities.contains(Factors.FIDO2)) {
            amr.add("hwk"); // hardware-backed / passkey
        }
        if (factorCount >= MFA_THRESHOLD) {
            amr.add("mfa");
        }
        return amr;
    }
}
