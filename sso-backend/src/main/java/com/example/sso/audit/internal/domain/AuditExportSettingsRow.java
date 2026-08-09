package com.example.sso.audit.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * The single collector row. Its id is fixed at {@link #ONLY}, and the schema enforces that with a check
 * constraint — "there is exactly one" is a fact the database can hold rather than something every writer has
 * to remember, the same arrangement {@code audit_chain_head} uses.
 *
 * <p>Deliberately not extending the shared UUID-keyed entity base: the identity here is not a generated key
 * but the constant that makes the row the only one.
 */
@Entity
@Table(name = "audit_export_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA only
public class AuditExportSettingsRow {

    /** The only id this table ever holds; the schema refuses anything else. */
    public static final short ONLY = 1;

    @Id
    private short id = ONLY;

    @Column(name = "endpoint_url", nullable = false)
    private String endpointUrl;

    /** SecretCipher ciphertext. The plaintext credential never reaches this table, a log, or a view. */
    @Column(name = "credential_encrypted", nullable = false)
    private String credentialEncrypted;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    public AuditExportSettingsRow(String endpointUrl, String credentialEncrypted, boolean enabled, UUID updatedBy) {
        this.id = ONLY;
        replaceWith(endpointUrl, credentialEncrypted, enabled, updatedBy);
    }

    /** Re-points the collector. One row means saving again REPLACES rather than adding a second destination. */
    public final void replaceWith(String endpointUrl, String credentialEncrypted, boolean enabled, UUID updatedBy) {
        this.endpointUrl = endpointUrl;
        this.credentialEncrypted = credentialEncrypted;
        this.enabled = enabled;
        this.updatedBy = updatedBy;
        this.updatedAt = Instant.now();
    }
}
