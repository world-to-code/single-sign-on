package com.example.sso.directory.internal.domain;

import com.example.sso.directory.DirectoryConnectorKind;
import com.example.sso.shared.domain.AuditedEntity;
import com.example.sso.tenancy.OrgOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A tenant's directory connection. {@code bindPasswordEncrypted} is SecretCipher ciphertext — the plaintext is
 * never stored, logged, audited or returned; the sync decrypts it only to bind.
 *
 * <p>{@code externalIdAttribute} is the correlation contract: it must name the same directory attribute that
 * put {@code external_id} on the local account, or the sync matches nothing and reports it.
 */
@Entity
@Table(name = "directory_connector")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DirectoryConnector extends AuditedEntity implements OrgOwned {

    @Column(name = "org_id")
    private UUID orgId;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(name = "display_name", nullable = false, columnDefinition = "text")
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private DirectoryConnectorKind kind;

    @Column(nullable = false)
    private boolean enabled;

    @Column(nullable = false, columnDefinition = "text")
    private String host;

    @Column(nullable = false)
    private int port;

    @Column(name = "use_ssl", nullable = false)
    private boolean useSsl;

    @Column(name = "start_tls", nullable = false)
    private boolean startTls;

    /** Null for an anonymous bind. */
    @Column(name = "bind_dn", columnDefinition = "text")
    private String bindDn;

    @Column(name = "bind_password_encrypted", columnDefinition = "text")
    private String bindPasswordEncrypted;

    @Column(name = "base_dn", nullable = false, columnDefinition = "text")
    private String baseDn;

    @Column(name = "user_filter", nullable = false, columnDefinition = "text")
    private String userFilter;

    @Column(name = "external_id_attribute", nullable = false, length = 64)
    private String externalIdAttribute;

    /** The administrator who last saved this connector; NULL is "unknown" and fails closed at grant time. */
    @Column(name = "configured_by")
    private UUID configuredBy;

    public static DirectoryConnector create(UUID orgId, String name, DirectoryConnectorKind kind) {
        DirectoryConnector connector = new DirectoryConnector();
        connector.orgId = orgId;
        connector.name = name;
        connector.kind = kind;
        return connector;
    }

    /** Repoints the connector (intent-revealing mutation, not a JavaBean setter); the name is immutable. */
    /** Records who vouched for this configuration — see {@code V124} for why the grant path needs to know. */
    public void configuredBy(UUID actorId) {
        this.configuredBy = actorId;
    }

    public void reconfigure(String displayName, boolean enabled, String host, int port, boolean useSsl,
            boolean startTls, String bindDn, String bindPasswordEncrypted, String baseDn, String userFilter,
            String externalIdAttribute) {
        this.displayName = displayName;
        this.enabled = enabled;
        this.host = host;
        this.port = port;
        this.useSsl = useSsl;
        this.startTls = startTls;
        this.bindDn = bindDn;
        this.bindPasswordEncrypted = bindPasswordEncrypted;
        this.baseDn = baseDn;
        this.userFilter = userFilter;
        this.externalIdAttribute = externalIdAttribute;
    }
}
