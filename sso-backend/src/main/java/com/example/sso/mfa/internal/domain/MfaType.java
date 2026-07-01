package com.example.sso.mfa.internal.domain;

/**
 * Supported multi-factor authentication factor types.
 *
 * <ul>
 *   <li>{@code TOTP} — authenticator-app time-based one-time password (custom factor; not built into Spring Security 7).</li>
 *   <li>{@code OTT} — one-time token delivered by email/magic-link (built into Spring Security 7).</li>
 *   <li>{@code WEBAUTHN} — passkeys / FIDO2 (built into Spring Security 7).</li>
 * </ul>
 */
public enum MfaType {
    TOTP,
    OTT,
    WEBAUTHN
}
