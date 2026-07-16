package com.example.sso.admin.internal.mapping.api;

import com.example.sso.mapping.MappingCondition;
import com.example.sso.metadata.AttributeOperator;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * One condition of a mapping-rule request: {@code attrKey <attrOp> attrValue}. A rule supports only the positive
 * operators EQUALS (value required) and EXISTS (value omitted); a missing operator defaults to EQUALS. Key/value
 * bounds mirror the metadata store.
 */
public record MappingConditionRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]*") String attrKey,
        AttributeOperator attrOp,
        @Size(max = 255) String attrValue) {

    public MappingCondition toCondition() {
        AttributeOperator op = AttributeOperator.orDefault(attrOp);
        return new MappingCondition(attrKey, op, op == AttributeOperator.EQUALS ? attrValue : null);
    }

    @AssertTrue(message = "a mapping rule supports only EQUALS or EXISTS")
    boolean isMappableOperator() {
        return AttributeOperator.mappable(attrOp);
    }

    @AssertTrue(message = "value is required for EQUALS and must be empty for EXISTS")
    boolean isValueConsistentWithOperator() {
        return AttributeOperator.valueConsistent(attrOp, attrValue);
    }
}
