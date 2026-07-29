package com.example.sso.mfa;

/**
 * The SMS gateways this deployment can send through.
 *
 * <p>A code-bound enum rather than free-text configuration, for the reason the identity-provider presets are:
 * each value implies an endpoint, an authentication scheme and a request shape that only the matching client
 * knows how to build. A value with no client would be a configuration a tenant could save and then silently
 * never deliver from.
 *
 * <p>The split is regional, and it is the deciding factor rather than a preference. Korea requires the sending
 * number to be pre-registered with the carriers (발신번호 사전등록) against the sending account, which the
 * domestic providers are built around and the global ones are not — routing Korean traffic through Twilio is
 * both costly and awkward about the sender. So a Korean tenant wants {@link #SOLAPI} and an international one
 * wants {@link #TWILIO}, and the point of this being per-tenant is that one deployment can serve both.
 */
public enum SmsProvider {

    /** Solapi (formerly CoolSMS). REST + HMAC-SHA256; the common Korean choice. */
    SOLAPI,

    /** Twilio. REST + HTTP Basic; the international default. */
    TWILIO
}
