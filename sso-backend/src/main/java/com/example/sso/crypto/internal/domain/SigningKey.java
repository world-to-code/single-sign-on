package com.example.sso.crypto.internal.domain;
import com.example.sso.shared.domain.AuditedEntity;
import com.example.sso.tenancy.OrgOwned;

import jakarta.persistence.*;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;


/**
 * A persisted RSA signing key for OIDC token signing. New keys are active; rotation
 * deactivates older keys ({@link #deactivate()}) while keeping them published for
 * verification. No setters — created via constructor, transitioned via domain methods.
 * A key is either GLOBAL ({@code orgId} null — the platform key / fallback) or owned by one tenant.
 */
@Entity
@Table(name = "signing_key")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // for Hibernate only
public class SigningKey extends AuditedEntity implements OrgOwned {

    @Column(nullable = false, unique = true, length = 64)
    private String kid;

    // NULL = a GLOBAL/platform key (also the fallback for tenants without their own); non-null = owned by
    // that organization. Fixed at creation.
    @Column(name = "org_id")
    private UUID orgId;

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

    /** A global/platform key (no owning org). */
    public SigningKey(String kid, String algorithm, String publicKey, String privateKey) {
        this.kid = kid;
        this.algorithm = algorithm;
        this.publicKey = publicKey;
        this.privateKey = privateKey;
    }

    /** A key owned by {@code orgId} (null = global). The org is fixed at creation. */
    public SigningKey(String kid, String algorithm, String publicKey, String privateKey, UUID orgId) {
        this(kid, algorithm, publicKey, privateKey);
        this.orgId = orgId;
    }

    public void deactivate() {
        this.active = false;
    }
}
