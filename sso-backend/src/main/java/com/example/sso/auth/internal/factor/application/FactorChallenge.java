package com.example.sso.auth.internal.factor.application;

/**
 * Result of preparing a factor step (what the SPA needs to present it): a TOTP enrollment
 * secret + QR, a WebAuthn options document, or just an acknowledgement that a code was sent.
 */
public record FactorChallenge(boolean prepared, String secret, String qrDataUri, String publicKeyOptions) {

    public static FactorChallenge none() {
        return new FactorChallenge(false, null, null, null);
    }

    public static FactorChallenge enrollment(String secret, String qrDataUri) {
        return new FactorChallenge(true, secret, qrDataUri, null);
    }

    public static FactorChallenge sent() {
        return new FactorChallenge(true, null, null, null);
    }

    public static FactorChallenge publicKey(String publicKeyOptions) {
        return new FactorChallenge(true, null, null, publicKeyOptions);
    }
}
