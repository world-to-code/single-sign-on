package com.example.sso.mapping.internal.domain;

import com.example.sso.mapping.MappingTargetKind;
import com.example.sso.metadata.AttributeOperator;
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

    /** The predicate value, present for an {@link AttributeOperator#EQUALS} rule and {@code null} for EXISTS. */
    @Column(name = "attr_value", length = 255)
    private String attrValue;

    /** How the predicate tests the key. A mapping rule allows only the positive operators EQUALS and EXISTS. */
    @Enumerated(EnumType.STRING)
    @Column(name = "attr_op", nullable = false, length = 16)
    private AttributeOperator attrOp;

    @Enumerated(EnumType.STRING)
    @Column(name = "then_kind", nullable = false, length = 16)
    private MappingTargetKind thenKind;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "created_by")
    private UUID createdBy;

    @Builder(access = AccessLevel.PRIVATE)
    private MappingRule(String attrKey, AttributeOperator attrOp, String attrValue, MappingTargetKind thenKind,
            UUID targetId, UUID orgId, UUID createdBy) {
        this.attrKey = attrKey;
        this.attrOp = attrOp;
        this.attrValue = attrValue;
        this.thenKind = thenKind;
        this.targetId = targetId;
        this.orgId = orgId;
        this.createdBy = createdBy;
    }

    /**
     * A rule assigning the users a predicate ({@code attrKey <attrOp> attrValue}) matches to a target
     * ({@code thenKind}), in the tier, authored by {@code createdBy} (null for a system/legacy rule). The author
     * is re-checked at each async materialize so a since-demoted author's rule stops handing out grants they
     * could no longer make by hand.
     */
    public static MappingRule of(String attrKey, AttributeOperator attrOp, String attrValue,
            MappingTargetKind thenKind, UUID targetId, UUID orgId, UUID createdBy) {
        return builder().attrKey(attrKey).attrOp(attrOp).attrValue(attrValue).thenKind(thenKind).targetId(targetId)
                .orgId(orgId).createdBy(createdBy).build();
    }

    /** Repoint the rule's predicate, kind and target (intent-revealing; the tier is fixed). */
    public void redefine(String attrKey, AttributeOperator attrOp, String attrValue, MappingTargetKind thenKind,
            UUID targetId) {
        this.attrKey = attrKey;
        this.attrOp = attrOp;
        this.attrValue = attrValue;
        this.thenKind = thenKind;
        this.targetId = targetId;
    }

    /** Re-stamp the vouching author — an update re-authorizes the target, so the updater becomes the author. */
    public void restampAuthor(UUID createdBy) {
        this.createdBy = createdBy;
    }
}
