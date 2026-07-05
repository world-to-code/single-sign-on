package com.example.sso.crypto.internal.application;

import com.example.sso.crypto.RsaKeyService;
import com.example.sso.crypto.SecretCipher;
import com.example.sso.crypto.internal.domain.SigningKey;
import com.example.sso.crypto.internal.domain.SigningKeyRepository;
import com.example.sso.tenancy.OrgContext;
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
 * Default {@link RsaKeyService}. Ensures an active key exists on startup and builds the
 * Nimbus {@link JWKSet} consumed by the OAuth2 Authorization Server's JWK source.
 * The active key signs; all keys are published (for verification across rotation).
 */
@Service
public class RsaKeyServiceImpl implements RsaKeyService, ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RsaKeyServiceImpl.class);

    private final SigningKeyRepository repository;
    private final SecretCipher secretCipher;
    private final OrgContext orgContext;
    private final int keySize;

    public RsaKeyServiceImpl(SigningKeyRepository repository, SecretCipher secretCipher, OrgContext orgContext,
                             @Value("${sso.crypto.rsa-key-size:2048}") int keySize) {
        this.repository = repository;
        this.secretCipher = secretCipher;
        this.orgContext = orgContext;
        this.keySize = keySize;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        ensureActiveKey();
    }

    @Transactional
    public SigningKey ensureActiveKey() {
        return repository.findFirstByActiveTrueAndOrgIdIsNullOrderByCreatedAtDesc()
                .orElseGet(this::generateAndStore);
    }

    @Override
    @Transactional
    public String rotate() {
        repository.findFirstByActiveTrueAndOrgIdIsNullOrderByCreatedAtDesc().ifPresent(SigningKey::deactivate);

        SigningKey rotated = generateAndStore();
        log.info("Rotated OIDC signing key; new active kid={}", rotated.getKid());

        return rotated.getKid();
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

    @Override
    @Transactional(readOnly = true)
    public JWKSet buildJwkSet() {
        return new JWKSet((JWK) toRsaKey(activeSigningKey()));
    }

    // The active signing key for the CURRENT tenant context (bound from the request host / session): the
    // org's own key if it has one, otherwise the GLOBAL key — so a tenant without its own key still signs
    // verifiably under its issuer (its JWKS then publishes the global key). Platform/unbound → global.
    private SigningKey activeSigningKey() {
        return orgContext.currentOrg()
                .flatMap(repository::findFirstByActiveTrueAndOrgIdOrderByCreatedAtDesc)
                .or(repository::findFirstByActiveTrueAndOrgIdIsNullOrderByCreatedAtDesc)
                .orElseThrow(() -> new IllegalStateException("No active signing key (global key missing)"));
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
