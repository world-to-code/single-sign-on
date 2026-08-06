package com.example.sso.webauthn;

import jakarta.servlet.http.HttpServletRequest;

/**
 * What KIND of passkey this session most recently proved possession of.
 *
 * <p>A passkey is not one thing. A device-bound credential lives in a security key or a secure enclave and
 * cannot leave it; a SYNCED credential is copied into the platform's account keychain by design, so possession
 * of it is not possession of any particular device. RFC 8176 draws exactly that line — {@code hwk} for a
 * hardware-secured key, {@code swk} for a software-secured one — and a relying party gating a high-assurance
 * action on a physical key acts on which one it is told.
 *
 * <p>The distinction is known only inside the ceremony, and needed on a later request: passwordless login
 * answers {@code /login/webauthn} and finalizes on a separate {@code /api/auth/complete}. So it is recorded
 * against the session as the assertion happens, and read here when the factor is granted.
 */
public interface PasskeyAssurance {

    /**
     * Whether the passkey last asserted in this session reported itself backup-eligible — a synced credential.
     *
     * <p>False when no passkey has been asserted, which is not a claim about anything: callers ask this only
     * once a FIDO2 factor has been granted, and a session with no record has nothing to downgrade.
     */
    boolean lastAssertionWasSoftwareBacked(HttpServletRequest request);
}
