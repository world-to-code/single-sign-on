package com.example.sso.mapping.internal.domain;

import com.example.sso.shared.domain.AuditedEntity;
import com.example.sso.tenancy.OrgOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Provenance of one assignment a {@link MappingRule} materialized: the exact (rule, user, target) it added.
 * Lets a retract (rule deleted, or the user stops matching) remove only rule-managed assignments, never a
 * manually-made one. Immutable.
 */
@Entity
@Table(name = "mapping_rule_membership")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA only
public class MappingRuleMembership extends AuditedEntity implements OrgOwned {

    @Column(name = "rule_id", nullable = false)
    private UUID ruleId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @Column(name = "org_id")
    private UUID orgId;

    @Builder(access = AccessLevel.PRIVATE)
    private MappingRuleMembership(UUID ruleId, UUID userId, UUID targetId, UUID orgId) {
        this.ruleId = ruleId;
        this.userId = userId;
        this.targetId = targetId;
        this.orgId = orgId;
    }

    /** A record that {@code ruleId} added {@code userId} to {@code targetId} in the given tier. */
    public static MappingRuleMembership of(UUID ruleId, UUID userId, UUID targetId, UUID orgId) {
        return builder().ruleId(ruleId).userId(userId).targetId(targetId).orgId(orgId).build();
    }
}
