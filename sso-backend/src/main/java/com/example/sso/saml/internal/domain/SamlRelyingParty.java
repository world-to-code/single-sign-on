package com.example.sso.saml.internal.domain;

import com.example.sso.shared.domain.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A SAML 2.0 service provider that trusts this IdP, with its per-RP security policy and (optional)
 * certificates. Created fully-formed via constructor; mutated only through {@link #update}.
 */
@Entity
@Table(name = "saml_relying_party")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class SamlRelyingParty extends AuditedEntity {

    public static final String NAMEID_EMAIL = "urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress";

    @Column(name = "entity_id", nullable = false, unique = true, length = 512)
    private String entityId;

    @Column(name = "acs_url", nullable = false, length = 1024)
    private String acsUrl;

    @Column(name = "name_id_format", nullable = false, length = 256)
    private String nameIdFormat = NAMEID_EMAIL;

    @Embedded
    private SamlSecuritySettings security = SamlSecuritySettings.defaults();

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
        this.security = settings;
        this.signingCertificate = signingCertificate;
        this.encryptionCertificate = encryptionCertificate;
        this.spLoginUrl = spLoginUrl == null || spLoginUrl.isBlank() ? null : spLoginUrl.trim();
    }

    // The per-RP security knobs live in the embedded value object; these delegate to preserve callers.
    public boolean isSignAssertion() {
        return security.signAssertion();
    }

    public boolean isSignResponse() {
        return security.signResponse();
    }

    public boolean isEncryptAssertion() {
        return security.encryptAssertion();
    }

    public String getSignatureAlgorithm() {
        return security.signatureAlgorithm();
    }

    public String getDataEncryptionAlgorithm() {
        return security.dataEncryptionAlgorithm();
    }

    public String getKeyTransportAlgorithm() {
        return security.keyTransportAlgorithm();
    }

    public boolean isWantAuthnRequestsSigned() {
        return security.wantAuthnRequestsSigned();
    }

    public boolean isAllowIdpInitiated() {
        return security.allowIdpInitiated();
    }
}
