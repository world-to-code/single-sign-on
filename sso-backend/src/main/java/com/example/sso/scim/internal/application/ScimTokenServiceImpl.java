package com.example.sso.scim.internal.application;

import com.example.sso.scim.ScimTokenService;
import com.example.sso.scim.internal.domain.ScimToken;
import com.example.sso.scim.internal.domain.ScimTokenRepository;
import lombok.RequiredArgsConstructor;
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
 * Default {@link ScimTokenService}. Issues and validates SCIM bearer tokens; the plaintext token is
 * returned only at issuance and only its SHA-256 hash is stored.
 */
@Service
@RequiredArgsConstructor
public class ScimTokenServiceImpl implements ScimTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ScimTokenRepository tokens;

    @Override
    @Transactional
    public String issue(String description, Duration ttl) {
        String raw = randomToken();
        Instant expiresAt = ttl == null ? null : Instant.now().plus(ttl);
        tokens.save(new ScimToken(description, hash(raw), expiresAt));
        return raw;
    }

    @Override
    @Transactional
    public void ensureToken(String rawToken, String description) {
        String hash = hash(rawToken);
        if (!tokens.existsByTokenHash(hash)) {
            tokens.save(new ScimToken(description, hash, null));
        }
    }

    @Override
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
