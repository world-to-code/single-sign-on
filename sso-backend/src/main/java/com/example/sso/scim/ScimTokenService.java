package com.example.sso.scim;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Issues and validates SCIM bearer tokens. The plaintext token is returned only at
 * issuance; only its SHA-256 hash is stored.
 */
@Service
public class ScimTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ScimTokenRepository tokens;

    public ScimTokenService(ScimTokenRepository tokens) {
        this.tokens = tokens;
    }

    /** Issues a new token and returns its plaintext value (store it now — it is not recoverable). */
    @Transactional
    public String issue(String description, Duration ttl) {
        String raw = randomToken();
        Instant expiresAt = ttl == null ? null : Instant.now().plus(ttl);
        tokens.save(new ScimToken(description, hash(raw), expiresAt));
        return raw;
    }

    /** Ensures a specific token exists (used to seed a stable dev token). Idempotent. */
    @Transactional
    public void ensureToken(String rawToken, String description) {
        String hash = hash(rawToken);
        if (!tokens.existsByTokenHash(hash)) {
            tokens.save(new ScimToken(description, hash, null));
        }
    }

    @Transactional(readOnly = true)
    public boolean isValid(String rawToken) {
        return tokens.findByTokenHash(hash(rawToken))
                .map(token -> token.isActiveAt(Instant.now()))
                .orElse(false);
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
