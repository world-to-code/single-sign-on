package com.example.sso.crypto.internal.application;

import com.example.sso.crypto.RsaKeyService;
import com.example.sso.crypto.SecretCipher;
import com.example.sso.crypto.internal.domain.SigningKey;
import com.example.sso.crypto.internal.domain.SigningKeyRepository;
import com.example.sso.crypto.internal.domain.SigningKeyRetention;
import com.example.sso.crypto.internal.domain.SigningKeyRetentionRepository;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.ForbiddenException;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Default {@link RsaKeyService}. Ensures an active key exists on startup and builds the
 * Nimbus {@link JWKSet} consumed by the OAuth2 Authorization Server's JWK source.
 * The active key signs (published first); rotated-away keys stay published up to the acting tier's
 * retention setting, so tokens they signed remain verifiable across a rotation.
 */
@Service
public class RsaKeyServiceImpl implements RsaKeyService, ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RsaKeyServiceImpl.class);

    private final SigningKeyRepository repository;
    private final SigningKeyRetentionRepository retentionRepository;
    private final SecretCipher secretCipher;
    private final OrgContext orgContext;
    private final int keySize;
    private final int defaultRetainedInactiveKeys;
    private final int maxRetainedInactiveKeys;

    public RsaKeyServiceImpl(SigningKeyRepository repository, SigningKeyRetentionRepository retentionRepository,
                             SecretCipher secretCipher, OrgContext orgContext,
                             @Value("${sso.crypto.rsa-key-size:2048}") int keySize,
                             @Value("${sso.crypto.jwks-retained-inactive-keys}") int defaultRetainedInactiveKeys,
                             @Value("${sso.crypto.jwks-max-retained-inactive-keys}") int maxRetainedInactiveKeys) {
        this.repository = repository;
        this.retentionRepository = retentionRepository;
        this.secretCipher = secretCipher;
        this.orgContext = orgContext;
        this.keySize = keySize;
        this.defaultRetainedInactiveKeys = defaultRetainedInactiveKeys;
        this.maxRetainedInactiveKeys = maxRetainedInactiveKeys;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        ensureActiveKey();
    }

    @Transactional
    public SigningKey ensureActiveKey() {
        // Startup runs with no org bound — ensure the GLOBAL platform key exists.
        return repository.findFirstByActiveTrueAndOrgIdIsNullOrderByCreatedAtDesc()
                .orElseGet(() -> generateAndStore(null));
    }

    @Override
    @Transactional
    public String rotate() {
        // Rotate the signing key of the CURRENT tier: a tenant admin (org bound) rotates its OWN org key
        // (creating one on first rotation); the platform admin (no org bound) rotates the global key. RLS
        // ensures a tenant can only ever deactivate/write its own tier's rows.
        UUID org = orgContext.currentOrg().orElse(null);
        activeKeyForTier(org).ifPresent(SigningKey::deactivate);

        SigningKey rotated = generateAndStore(org);
        log.info("Rotated OIDC signing key for {}; new active kid={}",
                org == null ? "the platform" : "org " + org, rotated.getKid());

        return rotated.getKid();
    }

    private Optional<SigningKey> activeKeyForTier(UUID org) {
        return org == null
                ? repository.findFirstByActiveTrueAndOrgIdIsNullOrderByCreatedAtDesc()
                : repository.findFirstByActiveTrueAndOrgIdOrderByCreatedAtDesc(org);
    }

    private SigningKey generateAndStore(UUID org) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(keySize);
            KeyPair pair = generator.generateKeyPair();

            SigningKey key = new SigningKey(
                    UUID.randomUUID().toString(),
                    "RS256",
                    Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
                    secretCipher.encrypt(Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded())),
                    org);

            SigningKey saved = repository.save(key);
            log.info("Generated new RSA signing key kid={}", saved.getKid());

            return saved;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate RSA signing key", e);
        }
    }

    // The keys of the CURRENT tenant context (bound from the request host / session): the org's own keys
    // if it has an active one, otherwise the GLOBAL keys — so a tenant without its own key still signs
    // verifiably under its issuer (its JWKS then publishes the global keys). Platform/unbound → global.
    // The ACTIVE key comes first (the JWT encoder signs with the first match); rotated-away keys stay
    // published up to the SERVING tier's retention setting so tokens they signed verify until they expire.
    @Override
    @Transactional(readOnly = true)
    public JWKSet buildJwkSet() {
        UUID org = orgContext.currentOrg().orElse(null);
        List<SigningKey> keys = publishedKeysForTier(org);
        if (org != null && hasNoActiveKey(keys)) {
            keys = publishedKeysForTier(null);
        }
        if (hasNoActiveKey(keys)) {
            throw new IllegalStateException("No active signing key (global key missing)");
        }
        return new JWKSet(keys.stream()
                .map(key -> (JWK) toRsaKey(key))
                .toList());
    }

    @Override
    @Transactional(readOnly = true)
    public int retainedInactiveKeys() {
        return retentionForTier(orgContext.currentOrg().orElse(null));
    }

    @Override
    @Transactional
    public int updateRetainedInactiveKeys(int retainedInactiveKeys) {
        if (retainedInactiveKeys < 0 || retainedInactiveKeys > maxRetainedInactiveKeys) {
            throw BadRequestException.of("crypto.retention.outOfRange", maxRetainedInactiveKeys);
        }
        UUID org = orgContext.currentOrg().orElse(null);
        if (org == null && !orgContext.isPlatform()) {
            // Fail closed: the global default is inherited by every tenant that has not customized its own,
            // so only the platform context may write it (mirror of the admin-portal-settings global write).
            throw new ForbiddenException("only a platform administrator may edit the global signing-key retention");
        }
        SigningKeyRetention row = (org == null
                ? retentionRepository.findByOrgIdIsNull()
                : retentionRepository.findByOrgId(org))
                .orElseGet(() -> new SigningKeyRetention(org, retainedInactiveKeys));
        row.update(retainedInactiveKeys);
        try {
            // Flush in-method so a concurrent first-save racing the tier's partial unique index surfaces
            // here as a client-visible conflict, not a commit-time 500.
            return retentionRepository.saveAndFlush(row).getRetainedInactiveKeys();
        } catch (DataIntegrityViolationException e) {
            throw ConflictException.of("crypto.retention.concurrentUpdate");
        }
    }

    /** The tier's keys bounded IN THE QUERY to active + its retention worth of rotated-away keys. */
    private List<SigningKey> publishedKeysForTier(UUID org) {
        Limit publication = Limit.of(1 + retentionForTier(org));
        return org == null
                ? repository.findByOrgIdIsNullOrderByActiveDescCreatedAtDesc(publication)
                : repository.findByOrgIdOrderByActiveDescCreatedAtDesc(org, publication);
    }

    /** Keys are ordered active-first, so an inactive (or missing) head means the tier cannot sign. */
    private boolean hasNoActiveKey(List<SigningKey> keys) {
        return keys.isEmpty() || !keys.getFirst().isActive();
    }

    /**
     * The tier's retention: its own row, else the global default row, else the configured default —
     * clamped to the configured maximum so a stored value can never blow up the publication limit.
     */
    private int retentionForTier(UUID org) {
        int retained = Optional.ofNullable(org).flatMap(retentionRepository::findByOrgId)
                .or(retentionRepository::findByOrgIdIsNull)
                .map(SigningKeyRetention::getRetainedInactiveKeys)
                .orElse(defaultRetainedInactiveKeys);
        return Math.min(retained, maxRetainedInactiveKeys);
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
