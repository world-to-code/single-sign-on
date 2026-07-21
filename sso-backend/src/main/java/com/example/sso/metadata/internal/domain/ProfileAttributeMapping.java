package com.example.sso.metadata.internal.domain;

import com.example.sso.shared.domain.AuditedEntity;
import com.example.sso.tenancy.OrgOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;

/** One attribute carried from a source profile into a target profile. See {@code V127}. */
@Entity
@Table(name = "profile_attribute_mapping")
@Getter
public class ProfileAttributeMapping extends AuditedEntity implements OrgOwned {

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "source_profile_id", nullable = false)
    private UUID sourceProfileId;

    @Column(name = "source_attr_key", nullable = false, length = 64)
    private String sourceAttrKey;

    @Column(name = "target_profile_id", nullable = false)
    private UUID targetProfileId;

    @Column(name = "target_attr_key", nullable = false, length = 64)
    private String targetAttrKey;

    protected ProfileAttributeMapping() {
    }

    public static ProfileAttributeMapping create(UUID orgId, UUID sourceProfileId, String sourceAttrKey,
            UUID targetProfileId, String targetAttrKey) {
        ProfileAttributeMapping mapping = new ProfileAttributeMapping();
        mapping.orgId = orgId;
        mapping.sourceProfileId = sourceProfileId;
        mapping.sourceAttrKey = sourceAttrKey;
        mapping.targetProfileId = targetProfileId;
        mapping.targetAttrKey = targetAttrKey;
        return mapping;
    }

    /** Aims an existing mapping at a different target; the source it reads is its identity. */
    public void retarget(UUID targetProfileId, String targetAttrKey) {
        this.targetProfileId = targetProfileId;
        this.targetAttrKey = targetAttrKey;
    }
}
