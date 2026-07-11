package com.example.sso.saml.internal.application;

import com.example.sso.crypto.SecretCipher;
import com.example.sso.saml.credential.SamlCredentialService;
import com.example.sso.saml.internal.domain.SamlCredential;
import com.example.sso.saml.internal.domain.SamlCredentialRepository;
import com.example.sso.tenancy.OrgContext;
import jakarta.annotation.PostConstruct;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * Default {@link SamlCredentialService}. Persists the signing credential in a PKCS#12 keystore so
 * issued assertions remain verifiable across restarts; the keystore is generated on first start if
 * absent.
 */
@Service
public class SamlCredentialServiceImpl implements SamlCredentialService {

    private static final Logger log = LoggerFactory.getLogger(SamlCredentialServiceImpl.class);
    private static final String ALIAS = "saml-idp";

    private final OrgContext orgContext;
    private final SamlCredentialRepository credentialRepository;
    private final SecretCipher secretCipher;

    private final Path keystorePath;
    private final char[] keystorePassword;
    private final String distinguishedName;
    private final int keySize;
    private final int certificateValidityDays;

    // The GLOBAL/platform signing material (from the file keystore) — the fallback for a tenant without its
    // own credential. volatile so a rotation is immediately visible to concurrent signers/metadata readers.
    private volatile PrivateKey globalPrivateKey;
    private volatile X509Certificate globalCertificate;

    public SamlCredentialServiceImpl(
            OrgContext orgContext, SamlCredentialRepository credentialRepository, SecretCipher secretCipher,
            @Value("${sso.saml.keystore-path:data/saml-idp.p12}") String keystorePath,
            @Value("${sso.saml.keystore-password:changeit}") String keystorePassword,
            @Value("${sso.saml.certificate-dn:CN=Mini SSO SAML IdP}") String distinguishedName,
            @Value("${sso.saml.key-size:2048}") int keySize,
            @Value("${sso.saml.certificate-validity-days:3650}") int certificateValidityDays) {
        this.orgContext = orgContext;
        this.credentialRepository = credentialRepository;
        this.secretCipher = secretCipher;
        this.keystorePath = Path.of(keystorePath);
        this.keystorePassword = keystorePassword.toCharArray();
        this.distinguishedName = distinguishedName;
        this.keySize = keySize;
        this.certificateValidityDays = certificateValidityDays;
    }

    // Signing material resolves to the CURRENT tenant's own credential if it has one, else the global
    // keystore credential — so a tenant's assertions are signed with its own key under its own entityID.

    @Override
    public X509Certificate getCertificate() {
        return orgCredential().map(c -> materializeCertificate(c.getCertificate())).orElse(globalCertificate);
    }

    @Override
    public PrivateKey getPrivateKey() {
        return orgCredential().map(c -> materializePrivateKey(c.getPrivateKey())).orElse(globalPrivateKey);
    }

    private Optional<SamlCredential> orgCredential() {
        return orgContext.currentOrg()
                .flatMap(credentialRepository::findFirstByActiveTrueAndOrgIdOrderByCreatedAtDesc);
    }

    private X509Certificate materializeCertificate(String base64Der) {
        try {
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(base64Der)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load tenant SAML certificate", e);
        }
    }

    private PrivateKey materializePrivateKey(String encryptedBase64Pkcs8) {
        try {
            byte[] pkcs8 = Base64.getDecoder().decode(secretCipher.decrypt(encryptedBase64Pkcs8));
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load tenant SAML private key", e);
        }
    }

    @PostConstruct
    void loadOrCreate() {
        try {
            if (Files.exists(keystorePath)) {
                load();
                log.info("Loaded SAML signing credential from {}", keystorePath);
            } else {
                generateAndStore();
                log.info("Generated SAML signing keystore at {}", keystorePath);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize SAML signing credential", e);
        }
    }

    @Override
    @Transactional
    public synchronized String rotate() {
        UUID org = orgContext.currentOrg().orElse(null);
        try {
            if (org == null) {
                generateAndStore(); // platform: rotate the global file-keystore credential
                log.info("Rotated global SAML signing key (serial {})", currentKeyId());
                return globalCertificate.getSerialNumber().toString(16);
            }
            // tenant: rotate its OWN DB credential (deactivate the old, generate + store a new one)
            credentialRepository.findFirstByActiveTrueAndOrgIdOrderByCreatedAtDesc(org)
                    .ifPresent(SamlCredential::deactivate);
            KeyPair keyPair = newKeyPair();
            X509Certificate cert = selfSign(keyPair);
            credentialRepository.save(new SamlCredential(org,
                    Base64.getEncoder().encodeToString(cert.getEncoded()),
                    secretCipher.encrypt(Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()))));
            log.info("Rotated SAML signing key for org {} (serial {})", org, cert.getSerialNumber().toString(16));
            return cert.getSerialNumber().toString(16);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to rotate SAML signing key", e);
        }
    }

    @Override
    public String currentKeyId() {
        return getCertificate().getSerialNumber().toString(16); // the current tenant's cred, or the global one
    }

    private void load() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystorePath)) {
            keyStore.load(in, keystorePassword);
        }

        this.globalPrivateKey = (PrivateKey) keyStore.getKey(ALIAS, keystorePassword);
        this.globalCertificate = (X509Certificate) keyStore.getCertificate(ALIAS);
    }

    private KeyPair newKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(keySize);
        return generator.generateKeyPair();
    }

    private void generateAndStore() throws Exception {
        KeyPair keyPair = newKeyPair();
        X509Certificate cert = selfSign(keyPair);

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry(ALIAS, keyPair.getPrivate(), keystorePassword, new Certificate[]{cert});
        if (keystorePath.getParent() != null) {
            Files.createDirectories(keystorePath.getParent());
        }
        try (OutputStream out = Files.newOutputStream(keystorePath)) {
            keyStore.store(out, keystorePassword);
        }

        this.globalPrivateKey = keyPair.getPrivate();
        this.globalCertificate = cert;
    }

    private X509Certificate selfSign(KeyPair keyPair) throws Exception {
        X500Name dn = new X500Name(distinguishedName);
        Instant now = Instant.now();
        Date notBefore = Date.from(now.minus(1, ChronoUnit.DAYS)); // 1-day clock-skew leeway
        Date notAfter = Date.from(now.plus(certificateValidityDays, ChronoUnit.DAYS));
        BigInteger serial = BigInteger.valueOf(System.currentTimeMillis())
                .shiftLeft(16).or(BigInteger.valueOf(new SecureRandom().nextInt(1 << 16)));

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate());
        var certBuilder = new JcaX509v3CertificateBuilder(
                dn, serial, notBefore, notAfter, dn, keyPair.getPublic());
        return new JcaX509CertificateConverter().getCertificate(certBuilder.build(signer));
    }
}
