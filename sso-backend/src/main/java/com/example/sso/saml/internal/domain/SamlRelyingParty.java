package com.example.sso.saml.internal.domain;

import com.example.sso.saml.relyingparty.SloBinding;
import com.example.sso.shared.domain.AuditedEntity;
import com.example.sso.tenancy.OrgOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
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
public class SamlRelyingParty extends AuditedEntity implements OrgOwned {

    public static final String NAMEID_EMAIL = "urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress";

    @Column(name = "entity_id", nullable = false, unique = true, length = 512)
    private String entityId;

    /** Owning tenant, or {@code null} for a GLOBAL relying party visible to every tenant. The entityId
     *  stays globally unique; RLS confines an org RP to its tenant's SSO (see {@code V52}). */
    @Column(name = "org_id")
    private UUID orgId;

    /** Friendly admin-set label shown in the app lists; falls back to {@code entityId} when unset. */
    @Column(name = "display_name", length = 256)
    private String displayName;

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

    /** The SP's Single Logout endpoint the IdP sends LogoutRequests to; null => SLO not configured. */
    @Column(name = "single_logout_url", length = 1024)
    private String singleLogoutUrl;

    /** How LogoutRequests are delivered (REDIRECT/POST front-channel, SOAP back-channel). Null => REDIRECT. */
    @Enumerated(EnumType.STRING)
    @Column(name = "slo_binding", length = 16)
    private SloBinding sloBinding;

    public SamlRelyingParty(String entityId, String acsUrl, String nameIdFormat, UUID orgId) {
        this.entityId = entityId;
        this.acsUrl = acsUrl;
        this.nameIdFormat = nameIdFormat;
        this.orgId = orgId;
    }

    /** GLOBAL relying party (no owning tenant) — used by the seeder for platform-wide SPs. */
    public SamlRelyingParty(String entityId, String acsUrl, String nameIdFormat) {
        this(entityId, acsUrl, nameIdFormat, null);
    }

    /** Admin edit of the display name, endpoint, security policy, SP certificates, and SLO endpoint
     *  (entityId is immutable). */
    public void update(String displayName, String acsUrl, String nameIdFormat, SamlSecuritySettings settings,
                       String signingCertificate, String encryptionCertificate, String spLoginUrl,
                       String singleLogoutUrl, SloBinding sloBinding) {
        this.displayName = displayName == null || displayName.isBlank() ? null : displayName.trim();
        this.acsUrl = acsUrl;
        this.nameIdFormat = nameIdFormat;
        this.security = settings;
        this.signingCertificate = signingCertificate;
        this.encryptionCertificate = encryptionCertificate;
        this.spLoginUrl = spLoginUrl == null || spLoginUrl.isBlank() ? null : spLoginUrl.trim();
        this.singleLogoutUrl = singleLogoutUrl == null || singleLogoutUrl.isBlank() ? null : singleLogoutUrl.trim();
        this.sloBinding = sloBinding;
    }

    /** The configured SLO delivery binding, defaulting to front-channel REDIRECT. */
    public SloBinding sloBinding() {
        return sloBinding == null ? SloBinding.REDIRECT : sloBinding;
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
