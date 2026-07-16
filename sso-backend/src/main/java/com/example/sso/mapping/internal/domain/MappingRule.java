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
 * An auto-mapping rule: users satisfying ALL of the rule's conditions (held in {@code mapping_rule_condition},
 * AND-combined) are assigned to {@link #targetId} — a group or a role, per {@link #thenKind}. Org-scoped
 * ({@code orgId} null = global). Assembled through the named factory over a private builder.
 */
@Entity
@Table(name = "mapping_rule")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA only
public class MappingRule extends AuditedEntity implements OrgOwned {

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
    private MappingRule(MappingTargetKind thenKind, UUID targetId, UUID orgId, UUID createdBy) {
        this.thenKind = thenKind;
        this.targetId = targetId;
        this.orgId = orgId;
        this.createdBy = createdBy;
    }

    /**
     * A rule assigning the users matching its conditions to a target ({@code thenKind}), in the tier, authored by
     * {@code createdBy} (null for a system/legacy rule). The conditions are persisted separately. The author is
     * re-checked at each async materialize so a since-demoted author's rule stops handing out grants they could
     * no longer make by hand.
     */
    public static MappingRule of(MappingTargetKind thenKind, UUID targetId, UUID orgId, UUID createdBy) {
        return builder().thenKind(thenKind).targetId(targetId).orgId(orgId).createdBy(createdBy).build();
    }

    /** Repoint the rule's kind and target (intent-revealing; the tier is fixed; conditions are managed separately). */
    public void redefine(MappingTargetKind thenKind, UUID targetId) {
        this.thenKind = thenKind;
        this.targetId = targetId;
    }

    /** Re-stamp the vouching author — an update re-authorizes the target, so the updater becomes the author. */
    public void restampAuthor(UUID createdBy) {
        this.createdBy = createdBy;
    }
}
