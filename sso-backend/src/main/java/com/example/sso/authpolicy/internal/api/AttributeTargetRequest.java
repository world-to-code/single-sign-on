package com.example.sso.authpolicy.internal.api;

import com.example.sso.metadata.AttributeOperator;
import com.example.sso.metadata.AttributePredicate;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Targets a policy at the users a metadata predicate matches ({@code key <operator> value}). Key is a bounded
 * identifier; value is bounded free text required for the value operators (EQUALS/NOT_EQUALS) and omitted for
 * the key operators (EXISTS/NOT_EXISTS). A missing operator defaults to EQUALS (backward compatibility).
 */
public record AttributeTargetRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]*") String key,
        AttributeOperator operator,
        @Size(max = 255) String value) {

    public AttributePredicate toPredicate() {
        AttributeOperator op = AttributeOperator.orDefault(operator);
        return new AttributePredicate(key, op, op.requiresValue() ? value : null);
    }

    @AssertTrue(message = "value is required for EQUALS/NOT_EQUALS and must be empty for EXISTS/NOT_EXISTS")
    boolean isValueConsistentWithOperator() {
        return AttributeOperator.valueConsistent(operator, value);
    }

    @AssertTrue(message = "a policy target supports EQUALS, NOT_EQUALS, EXISTS or NOT_EXISTS")
    boolean isTargetableOperator() {
        return AttributeOperator.targetable(operator);
    }
}
