package com.example.sso.mapping.internal.domain;

import com.example.sso.mapping.MappingCondition;
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
 * One condition of a {@link MappingRule}: {@code attrKey <attrOp> attrValue}. A rule's conditions are
 * AND-combined by the evaluator. Positive operators only (EQUALS with a value, EXISTS value-less). Org-scoped
 * ({@code orgId} null = global). Assembled through the named factory over a private builder.
 */
@Entity
@Table(name = "mapping_rule_condition")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA only
public class MappingRuleCondition extends AuditedEntity implements OrgOwned {

    @Column(name = "rule_id", nullable = false)
    private UUID ruleId;

    @Column(name = "attr_key", nullable = false, length = 64)
    private String attrKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "attr_op", nullable = false, length = 16)
    private AttributeOperator attrOp;

    @Column(name = "attr_value", length = 255)
    private String attrValue;

    @Column(name = "org_id")
    private UUID orgId;

    @Builder(access = AccessLevel.PRIVATE)
    private MappingRuleCondition(UUID ruleId, String attrKey, AttributeOperator attrOp, String attrValue,
            UUID orgId) {
        this.ruleId = ruleId;
        this.attrKey = attrKey;
        this.attrOp = attrOp;
        this.attrValue = attrValue;
        this.orgId = orgId;
    }

    /** A condition of {@code ruleId} in the tier: {@code attrKey <attrOp> attrValue} (value null for EXISTS). */
    public static MappingRuleCondition of(UUID ruleId, String attrKey, AttributeOperator attrOp, String attrValue,
            UUID orgId) {
        return builder().ruleId(ruleId).attrKey(attrKey).attrOp(attrOp).attrValue(attrValue).orgId(orgId).build();
    }

    /** This row projected to the public value object — the single home for the entity→value mapping. */
    public MappingCondition toValue() {
        return new MappingCondition(attrKey, attrOp, attrValue);
    }
}
