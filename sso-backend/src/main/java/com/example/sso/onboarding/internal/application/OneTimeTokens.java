package com.example.sso.onboarding.internal.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * Mints high-entropy one-time tokens ({@code SecureRandom}, 256-bit, URL-safe) and hashes them for at-rest
 * storage (SHA-256 hex). The raw token is returned only to the caller (for an emailed link); only its hash is
 * ever persisted, and lookup is by hash. Shared by the onboarding-invitation and self-service-signup flows so
 * this security-sensitive crypto lives in exactly one place and can't drift between them.
 */
@Component
public class OneTimeTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32; // 256-bit

    /** A fresh URL-safe raw token. Persist only its {@link #hash(String)}, never this value. */
    public String mint() {
        byte[] raw = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /** The SHA-256 hex hash of a raw token, for at-rest storage and by-hash lookup. */
    public String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
