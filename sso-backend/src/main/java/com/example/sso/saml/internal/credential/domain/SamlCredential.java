package com.example.sso.saml.internal.credential.domain;

import com.example.sso.shared.domain.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A tenant's own SAML signing credential: a self-signed X.509 certificate and its RSA private key (stored
 * encrypted at rest via SecretCipher). Only per-organization credentials live here — the global/platform
 * credential remains the on-disk keystore. New credentials are active; rotation deactivates the old one.
 */
@Entity
@Table(name = "saml_credential")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class SamlCredential extends AuditedEntity {

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    /** Base64 X.509 (DER) self-signed certificate. */
    @Column(nullable = false, columnDefinition = "text")
    private String certificate;

    /** Base64 PKCS#8 private key, stored encrypted at rest via SecretCipher. */
    @Column(name = "private_key", nullable = false, columnDefinition = "text")
    private String privateKey;

    @Column(nullable = false)
    private boolean active = true;

    public SamlCredential(UUID orgId, String certificate, String privateKey) {
        this.orgId = orgId;
        this.certificate = certificate;
        this.privateKey = privateKey;
    }

    public void deactivate() {
        this.active = false;
    }
}
