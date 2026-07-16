package com.example.sso.mapping.internal.domain;

import com.example.sso.shared.domain.AuditedEntity;
import com.example.sso.tenancy.OrgOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Provenance of one assignment a {@link MappingRule} materialized: the exact (rule, user, target) it added.
 * Lets a retract (rule deleted, or the user stops matching) remove only rule-managed assignments, never a
 * manually-made one. Immutable and read-only in the domain: rows are written by the evaluator's idempotent
 * {@code ON CONFLICT DO NOTHING} upsert (race-proof provenance claim), and only ever hydrated by JPA here.
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
}
