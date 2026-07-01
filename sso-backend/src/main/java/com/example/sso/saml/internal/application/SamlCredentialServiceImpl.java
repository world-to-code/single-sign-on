package com.example.sso.saml.internal.application;

import com.example.sso.saml.SamlCredentialService;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * Default {@link SamlCredentialService}. Persists the signing credential in a PKCS#12 keystore so
 * issued assertions remain verifiable across restarts; the keystore is generated on first start if
 * absent.
 */
@Service
public class SamlCredentialServiceImpl implements SamlCredentialService {

    private static final Logger log = LoggerFactory.getLogger(SamlCredentialServiceImpl.class);
    private static final String ALIAS = "saml-idp";

    private final Path keystorePath;
    private final char[] keystorePassword;
    private final String distinguishedName;
    private final int keySize;
    private final int certificateValidityDays;

    // Only the signing material is exposed; the keystore password is never published.
    // volatile so a rotation is immediately visible to concurrent signers/metadata readers.
    @Getter
    private volatile PrivateKey privateKey;
    @Getter
    private volatile X509Certificate certificate;

    public SamlCredentialServiceImpl(
            @Value("${sso.saml.keystore-path:data/saml-idp.p12}") String keystorePath,
            @Value("${sso.saml.keystore-password:changeit}") String keystorePassword,
            @Value("${sso.saml.certificate-dn:CN=Mini SSO SAML IdP}") String distinguishedName,
            @Value("${sso.saml.key-size:2048}") int keySize,
            @Value("${sso.saml.certificate-validity-days:3650}") int certificateValidityDays) {
        this.keystorePath = Path.of(keystorePath);
        this.keystorePassword = keystorePassword.toCharArray();
        this.distinguishedName = distinguishedName;
        this.keySize = keySize;
        this.certificateValidityDays = certificateValidityDays;
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
    public synchronized String rotate() {
        try {
            generateAndStore();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to rotate SAML signing key", e);
        }
        log.info("Rotated SAML signing key (serial {})", currentKeyId());
        return currentKeyId();
    }

    @Override
    public String currentKeyId() {
        return certificate.getSerialNumber().toString(16);
    }

    private void load() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystorePath)) {
            keyStore.load(in, keystorePassword);
        }
        this.privateKey = (PrivateKey) keyStore.getKey(ALIAS, keystorePassword);
        this.certificate = (X509Certificate) keyStore.getCertificate(ALIAS);
    }

    private void generateAndStore() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(keySize);
        KeyPair keyPair = generator.generateKeyPair();
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
        this.privateKey = keyPair.getPrivate();
        this.certificate = cert;
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
