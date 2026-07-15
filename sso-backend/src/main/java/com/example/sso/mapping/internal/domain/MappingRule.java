package com.example.sso.mapping.internal.domain;

import com.example.sso.mapping.MappingTargetKind;
import com.example.sso.shared.domain.AuditedEntity;
import com.example.sso.tenancy.OrgOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * An auto-mapping rule: users carrying {@code attrKey = attrValue} are assigned to {@link #targetId} — a group
 * or a role, per {@link #thenKind}. Org-scoped ({@code orgId} null = global). Assembled through the named factory
 * over a private builder so field order stays contained to this class.
 */
@Entity
@Table(name = "mapping_rule")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA only
public class MappingRule extends AuditedEntity implements OrgOwned {

    @Column(name = "attr_key", nullable = false, length = 64)
    private String attrKey;

    @Column(name = "attr_value", nullable = false, length = 255)
    private String attrValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "then_kind", nullable = false, length = 16)
    private MappingTargetKind thenKind;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @Column(name = "org_id")
    private UUID orgId;

    @Builder(access = AccessLevel.PRIVATE)
    private MappingRule(String attrKey, String attrValue, MappingTargetKind thenKind, UUID targetId, UUID orgId) {
        this.attrKey = attrKey;
        this.attrValue = attrValue;
        this.thenKind = thenKind;
        this.targetId = targetId;
        this.orgId = orgId;
    }

    /** A rule assigning the users carrying {@code attrKey = attrValue} to a target ({@code thenKind}), in the tier. */
    public static MappingRule of(String attrKey, String attrValue, MappingTargetKind thenKind, UUID targetId,
            UUID orgId) {
        return builder().attrKey(attrKey).attrValue(attrValue).thenKind(thenKind).targetId(targetId).orgId(orgId).build();
    }

    /** Repoint the rule's predicate, kind and target (intent-revealing; the tier is fixed). */
    public void redefine(String attrKey, String attrValue, MappingTargetKind thenKind, UUID targetId) {
        this.attrKey = attrKey;
        this.attrValue = attrValue;
        this.thenKind = thenKind;
        this.targetId = targetId;
    }
}
