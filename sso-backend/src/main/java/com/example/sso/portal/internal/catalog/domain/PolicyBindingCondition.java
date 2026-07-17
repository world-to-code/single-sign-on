package com.example.sso.portal.internal.catalog.domain;

import com.example.sso.metadata.AttributeOperator;
import com.example.sso.metadata.AttributePredicate;
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
 * One condition of an {@link PolicyBinding.SubjectType#ATTRIBUTE} binding: {@code attrKey <attrOp> attrValue/
 * attrValues}. A binding's conditions are AND-combined by the resolver — a user matches the binding only when they
 * satisfy ALL of them. Policy-targetable operators: EQUALS/NOT_EQUALS/CONTAINS carry a scalar value; EXISTS/
 * NOT_EXISTS carry none; IN carries a non-empty {@code attrValues} list. Org-scoped ({@code orgId} null = global).
 */
@Entity
@Table(name = "policy_binding_condition")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA only
public class PolicyBindingCondition extends AuditedEntity implements OrgOwned {

    @Column(name = "binding_id", nullable = false)
    private UUID bindingId;

    @Column(name = "attr_key", nullable = false, length = 64)
    private String attrKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "attr_op", nullable = false, length = 16)
    private AttributeOperator attrOp;

    @Column(name = "attr_value", length = 255)
    private String attrValue;

    /** The IN value list (a text[] column); empty/null for the scalar and value-less operators. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "attr_values")
    private List<String> attrValues;

    @Column(name = "org_id")
    private UUID orgId;

    @Builder(access = AccessLevel.PRIVATE)
    private PolicyBindingCondition(UUID bindingId, String attrKey, AttributeOperator attrOp, String attrValue,
            List<String> attrValues, UUID orgId) {
        this.bindingId = bindingId;
        this.attrKey = attrKey;
        this.attrOp = attrOp;
        this.attrValue = attrValue;
        this.attrValues = attrValues == null || attrValues.isEmpty() ? null : List.copyOf(attrValues);
        this.orgId = orgId;
    }

    /** A condition of {@code bindingId}: IN carries the predicate's {@code values}, the scalar operators its
     *  {@code value}, the key operators neither. */
    public static PolicyBindingCondition of(UUID bindingId, AttributePredicate predicate, UUID orgId) {
        return builder().bindingId(bindingId).attrKey(predicate.key()).attrOp(predicate.operator())
                .attrValue(predicate.value()).attrValues(predicate.values()).orgId(orgId).build();
    }

    /** This row projected to the public value object — the single home for the entity→predicate mapping. */
    public AttributePredicate toPredicate() {
        return attrOp.requiresValueList()
                ? AttributePredicate.in(attrKey, attrValues)
                : new AttributePredicate(attrKey, attrOp, attrValue);
    }
}
