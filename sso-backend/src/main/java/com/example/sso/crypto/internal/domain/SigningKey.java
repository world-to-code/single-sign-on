package com.example.sso.crypto.internal.domain;
import com.example.sso.shared.domain.AuditedEntity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;


/**
 * A persisted RSA signing key for OIDC token signing. New keys are active; rotation
 * deactivates older keys ({@link #deactivate()}) while keeping them published for
 * verification. No setters — created via constructor, transitioned via domain methods.
 */
@Entity
@Table(name = "signing_key")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class SigningKey extends AuditedEntity {

    @Column(nullable = false, unique = true, length = 64)
    private String kid;

    @Column(nullable = false, length = 16)
    private String algorithm;

    /** Base64-encoded X.509 SubjectPublicKeyInfo. */
    @Column(name = "public_key", nullable = false, columnDefinition = "text")
    private String publicKey;

    /** Base64-encoded PKCS#8 private key, stored encrypted at rest via SecretCipher. */
    @Column(name = "private_key", nullable = false, columnDefinition = "text")
    private String privateKey;

    @Column(nullable = false)
    private boolean active = true;

    public SigningKey(String kid, String algorithm, String publicKey, String privateKey) {
        this.kid = kid;
        this.algorithm = algorithm;
        this.publicKey = publicKey;
        this.privateKey = privateKey;
    }

    public void deactivate() {
        this.active = false;
    }
}
