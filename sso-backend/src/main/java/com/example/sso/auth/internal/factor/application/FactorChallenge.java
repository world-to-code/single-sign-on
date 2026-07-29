package com.example.sso.auth.internal.factor.application;

import java.time.Duration;

/**
 * Result of preparing a factor step (what the SPA needs to present it): a TOTP enrollment
 * secret + QR, a WebAuthn options document, or just an acknowledgement that a code was sent.
 *
 * @param expiresInSeconds how long the code just sent remains usable, so the screen can count down instead of
 *                         leaving somebody to discover the expiry by being rejected. Zero when the step issues
 *                         no expiring code.
 */
public record FactorChallenge(boolean prepared, String secret, String qrDataUri, String publicKeyOptions,
                              long expiresInSeconds) {

    public static FactorChallenge none() {
        return new FactorChallenge(false, null, null, null, 0);
    }

    public static FactorChallenge enrollment(String secret, String qrDataUri) {
        return new FactorChallenge(true, secret, qrDataUri, null, 0);
    }

    /** A code went out; {@code validFor} is how long the person has to use it. */
    public static FactorChallenge sent(Duration validFor) {
        return new FactorChallenge(true, null, null, null, validFor.toSeconds());
    }

    public static FactorChallenge publicKey(String publicKeyOptions) {
        return new FactorChallenge(true, null, null, publicKeyOptions, 0);
    }
}
