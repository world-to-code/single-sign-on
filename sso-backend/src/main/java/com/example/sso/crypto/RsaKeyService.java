package com.example.sso.crypto;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.UUID;

/**
 * Manages RSA signing keys: ensures an active key exists on startup, and builds the
 * Nimbus {@link JWKSet} consumed by the OAuth2 Authorization Server's JWK source.
 * The active key signs; all keys are published (for verification across rotation).
 */
@Service
public class RsaKeyService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RsaKeyService.class);

    private final SigningKeyRepository repository;
    private final SecretCipher secretCipher;
    private final int keySize;

    public RsaKeyService(SigningKeyRepository repository, SecretCipher secretCipher,
                         @Value("${sso.crypto.rsa-key-size:2048}") int keySize) {
        this.repository = repository;
        this.secretCipher = secretCipher;
        this.keySize = keySize;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        ensureActiveKey();
    }

    @Transactional
    public SigningKey ensureActiveKey() {
        return repository.findFirstByActiveTrueOrderByCreatedAtDesc()
                .orElseGet(this::generateAndStore);
    }

    /**
     * Rotates the signing key: the current active key is deactivated (kept published for
     * verifying already-issued tokens) and a new active key is generated to sign new ones.
     */
    @Transactional
    public SigningKey rotate() {
        repository.findFirstByActiveTrueOrderByCreatedAtDesc().ifPresent(SigningKey::deactivate);
        SigningKey rotated = generateAndStore();
        log.info("Rotated OIDC signing key; new active kid={}", rotated.getKid());
        return rotated;
    }

    private SigningKey generateAndStore() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(keySize);
            KeyPair pair = generator.generateKeyPair();

            SigningKey key = new SigningKey(
                    UUID.randomUUID().toString(),
                    "RS256",
                    Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
                    secretCipher.encrypt(Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded())));
            SigningKey saved = repository.save(key);
            log.info("Generated new RSA signing key kid={}", saved.getKid());
            return saved;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate RSA signing key", e);
        }
    }

    /**
     * Builds the JWK set used by the authorization server. Only the active key is exposed
     * so the JWT encoder can unambiguously select a signing key; rotation swaps which key
     * is active (relying parties re-fetch the JWKS to pick up the new key).
     */
    @Transactional(readOnly = true)
    public JWKSet buildJwkSet() {
        return new JWKSet((JWK) toRsaKey(ensureActiveKey()));
    }

    private RSAKey toRsaKey(SigningKey key) {
        try {
            KeyFactory factory = KeyFactory.getInstance("RSA");
            RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(key.getPublicKey())));
            RSAPrivateKey privateKey = (RSAPrivateKey) factory.generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(secretCipher.decrypt(key.getPrivateKey()))));
            return new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyID(key.getKid())
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load RSA key kid=" + key.getKid(), e);
        }
    }
}
