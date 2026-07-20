package com.example.sso.directory.internal.domain;

import com.example.sso.shared.domain.AuditedEntity;
import com.example.sso.tenancy.OrgOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Which directory attribute fills which declared profile attribute. */
@Entity
@Table(name = "directory_attribute_mapping")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DirectoryAttributeMapping extends AuditedEntity implements OrgOwned {

    @Column(name = "connector_id", nullable = false)
    private UUID connectorId;

    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "source_attribute", nullable = false, length = 64)
    private String sourceAttribute;

    /** An {@code attribute_definition.attr_key}; unresolvable ones fail at sync time, deliberately loudly. */
    @Column(name = "target_key", nullable = false, length = 64)
    private String targetKey;

    public static DirectoryAttributeMapping create(UUID connectorId, UUID orgId, String sourceAttribute,
            String targetKey) {
        DirectoryAttributeMapping mapping = new DirectoryAttributeMapping();
        mapping.connectorId = connectorId;
        mapping.orgId = orgId;
        mapping.sourceAttribute = sourceAttribute;
        mapping.targetKey = targetKey;
        return mapping;
    }

    /** Aims an existing mapping at a different profile attribute; the source it reads is its identity. */
    public void retarget(String targetKey) {
        this.targetKey = targetKey;
    }
}
