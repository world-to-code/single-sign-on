package com.example.sso.crypto;

/**
 * Keyed hash for OAuth client secrets the server generated itself (256-bit {@code SecureRandom} values).
 *
 * <p>A slow password hash exists to make guessing a human-chosen password expensive. A server-generated secret
 * cannot be guessed, so BCrypt only buys cost on every token request — load tests showed it dominating the token
 * endpoint. An HMAC keyed by a server secret keeps a leaked digest useless on its own and verifies in constant time.
 * It must never be used for a secret a person chose: that value still needs the slow hash.
 */
public interface ClientSecretHasher {

    /** The id carried in the stored value, in the same {@code {id}digest} shape Spring's password encoders use. */
    String ENCODING_ID = "hmac-sha256";

    /** Hashes a server-generated secret into its stored form; a value too short to be one is refused. */
    String encode(CharSequence rawSecret);

    /** Whether {@code encoded} was produced by this hasher (and so must be verified by it). */
    boolean handles(String encoded);

    /** Constant-time check of a presented secret against a stored value; any malformed value is a refusal. */
    boolean matches(CharSequence rawSecret, String encoded);
}
