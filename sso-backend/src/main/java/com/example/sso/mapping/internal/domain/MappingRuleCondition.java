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
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One condition of a {@link MappingRule}: {@code attrKey <attrOp> attrValue/attrValues}. A rule's conditions are
 * AND-combined by the evaluator. Positive operators only — EQUALS (scalar {@code attrValue}), EXISTS (neither),
 * IN ({@code attrValues} list). Org-scoped ({@code orgId} null = global). Assembled through the named factory.
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

    /** The IN value list (a text[] column); empty for the scalar/value-less operators. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "attr_values")
    private List<String> attrValues;

    @Column(name = "org_id")
    private UUID orgId;

    @Builder(access = AccessLevel.PRIVATE)
    private MappingRuleCondition(UUID ruleId, String attrKey, AttributeOperator attrOp, String attrValue,
            List<String> attrValues, UUID orgId) {
        this.ruleId = ruleId;
        this.attrKey = attrKey;
        this.attrOp = attrOp;
        this.attrValue = attrValue;
        this.attrValues = attrValues == null || attrValues.isEmpty() ? null : List.copyOf(attrValues);
        this.orgId = orgId;
    }

    /** A condition of {@code ruleId}: IN carries {@code attrValues}, EQUALS {@code attrValue}, EXISTS neither. */
    public static MappingRuleCondition of(UUID ruleId, MappingCondition condition, UUID orgId) {
        return builder().ruleId(ruleId).attrKey(condition.attrKey()).attrOp(condition.attrOp())
                .attrValue(condition.attrValue()).attrValues(condition.attrValues()).orgId(orgId).build();
    }

    /** This row projected to the public value object — the single home for the entity→value mapping. */
    public MappingCondition toValue() {
        return new MappingCondition(attrKey, attrOp, attrValue, attrValues == null ? List.of() : attrValues);
    }
}
