package com.example.sso.admin.internal.mapping.api;

import com.example.sso.mapping.MappingCondition;
import com.example.sso.metadata.AttributeOperator;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * One condition of a mapping-rule request: {@code attrKey <attrOp> attrValue/attrValues}. A rule supports only
 * the positive operators — EQUALS / CONTAINS (a scalar value), EXISTS (neither), IN (a non-empty
 * {@code attrValues} list); a missing operator defaults to EQUALS. Key/value bounds mirror the metadata store.
 */
public record MappingConditionRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]*") String attrKey,
        AttributeOperator attrOp,
        @Size(max = 255) String attrValue,
        @Size(max = 100) List<@NotBlank @Size(max = 255) String> attrValues) {

    /** A scalar or value-less condition request (EQUALS/EXISTS) — no value list. */
    public MappingConditionRequest(String attrKey, AttributeOperator attrOp, String attrValue) {
        this(attrKey, attrOp, attrValue, null);
    }

    public MappingCondition toCondition() {
        AttributeOperator op = AttributeOperator.orDefault(attrOp);
        // a scalar value for the value operators (EQUALS/CONTAINS), none for EXISTS
        return op == AttributeOperator.IN
                ? new MappingCondition(attrKey, op, null, attrValues)
                : new MappingCondition(attrKey, op, op.requiresValue() ? attrValue : null);
    }

    @AssertTrue(message = "a mapping rule supports only EQUALS, EXISTS, IN or CONTAINS")
    boolean isMappableOperator() {
        return AttributeOperator.mappable(attrOp);
    }

    @AssertTrue(message = "a value is required for EQUALS/CONTAINS and must be empty for EXISTS/IN")
    boolean isValueConsistentWithOperator() {
        return AttributeOperator.valueConsistent(attrOp, attrValue);
    }

    @AssertTrue(message = "a non-empty value list is required for IN and must be empty otherwise")
    boolean isValueListConsistentWithOperator() {
        return AttributeOperator.valueListConsistent(attrOp, attrValues);
    }
}
