package com.example.sso.saml;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * A SAML 2.0 service provider that trusts this IdP, with its per-RP security policy and (optional)
 * certificates. Created fully-formed via constructor; mutated only through {@link #update}.
 */
@Entity
@Table(name = "saml_relying_party")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class SamlRelyingParty {

    public static final String NAMEID_EMAIL = "urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "entity_id", nullable = false, unique = true, length = 512)
    private String entityId;

    @Column(name = "acs_url", nullable = false, length = 1024)
    private String acsUrl;

    @Column(name = "name_id_format", nullable = false, length = 256)
    private String nameIdFormat = NAMEID_EMAIL;

    @Column(name = "sign_assertion", nullable = false)
    private boolean signAssertion = true;

    @Column(name = "sign_response", nullable = false)
    private boolean signResponse = false;

    @Column(name = "encrypt_assertion", nullable = false)
    private boolean encryptAssertion = false;

    @Column(name = "signature_algorithm", nullable = false, length = 64)
    private String signatureAlgorithm = "RSA_SHA256";

    @Column(name = "data_encryption_algorithm", nullable = false, length = 32)
    private String dataEncryptionAlgorithm = "AES256_GCM";

    @Column(name = "key_transport_algorithm", nullable = false, length = 32)
    private String keyTransportAlgorithm = "RSA_OAEP";

    @Column(name = "want_authn_requests_signed", nullable = false)
    private boolean wantAuthnRequestsSigned = false;

    @Column(name = "allow_idp_initiated", nullable = false)
    private boolean allowIdpInitiated = true;

    /** SP signing certificate (PEM) — used to verify SP-signed AuthnRequests. */
    @Column(name = "signing_certificate", columnDefinition = "text")
    private String signingCertificate;

    /** SP encryption certificate (PEM) — the public key assertions are encrypted to. */
    @Column(name = "encryption_certificate", columnDefinition = "text")
    private String encryptionCertificate;

    /** The SP's SP-initiated login start URL; portal launch redirects here so the SP sends us an
     *  AuthnRequest (SP-initiated). Null => fall back to IdP-initiated (unsolicited) SSO. */
    @Column(name = "sp_login_url", length = 1024)
    private String spLoginUrl;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public SamlRelyingParty(String entityId, String acsUrl, String nameIdFormat) {
        this.entityId = entityId;
        this.acsUrl = acsUrl;
        this.nameIdFormat = nameIdFormat;
    }

    /** Admin edit of the endpoint, security policy, and SP certificates (entityId is immutable). */
    public void update(String acsUrl, String nameIdFormat, SamlSecuritySettings settings,
                       String signingCertificate, String encryptionCertificate, String spLoginUrl) {
        this.acsUrl = acsUrl;
        this.nameIdFormat = nameIdFormat;
        this.signAssertion = settings.signAssertion();
        this.signResponse = settings.signResponse();
        this.encryptAssertion = settings.encryptAssertion();
        this.signatureAlgorithm = settings.signatureAlgorithm();
        this.dataEncryptionAlgorithm = settings.dataEncryptionAlgorithm();
        this.keyTransportAlgorithm = settings.keyTransportAlgorithm();
        this.wantAuthnRequestsSigned = settings.wantAuthnRequestsSigned();
        this.allowIdpInitiated = settings.allowIdpInitiated();
        this.signingCertificate = signingCertificate;
        this.encryptionCertificate = encryptionCertificate;
        this.spLoginUrl = spLoginUrl == null || spLoginUrl.isBlank() ? null : spLoginUrl.trim();
    }
}
