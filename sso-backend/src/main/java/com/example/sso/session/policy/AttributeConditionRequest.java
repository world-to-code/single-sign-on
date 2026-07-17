package com.example.sso.session.policy;

import com.example.sso.metadata.AttributeOperator;
import com.example.sso.metadata.AttributePredicate;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * One condition of an attribute target ({@code key <operator> value/values}). Key is a bounded identifier; the
 * scalar operators (EQUALS/NOT_EQUALS/CONTAINS) carry a bounded {@code value}, IN a non-empty {@code values} list,
 * and the key operators (EXISTS/NOT_EXISTS) neither. A missing operator defaults to EQUALS. The conditions of one
 * target are AND-combined. The session twin of {@code authpolicy}'s request, kept in lockstep with it.
 */
public record AttributeConditionRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]*") String key,
        AttributeOperator operator,
        @Size(max = 255) String value,
        @Size(max = 100) List<@NotBlank @Size(max = 255) String> values) {

    /** A scalar or value-less condition (EQUALS/NOT_EQUALS/CONTAINS/EXISTS/NOT_EXISTS) — no value list. */
    public AttributeConditionRequest(String key, AttributeOperator operator, String value) {
        this(key, operator, value, null);
    }

    public AttributePredicate toPredicate() {
        AttributeOperator op = AttributeOperator.orDefault(operator);
        return op == AttributeOperator.IN
                ? AttributePredicate.in(key, values)
                : new AttributePredicate(key, op, op.requiresValue() ? value : null);
    }

    @AssertTrue(message = "value is required for EQUALS/NOT_EQUALS/CONTAINS and must be empty for EXISTS/NOT_EXISTS/IN")
    boolean isValueConsistentWithOperator() {
        return AttributeOperator.valueConsistent(operator, value);
    }

    @AssertTrue(message = "a non-empty value list is required for IN and must be empty otherwise")
    boolean isValueListConsistentWithOperator() {
        return AttributeOperator.valueListConsistent(operator, values);
    }

    @AssertTrue(message = "a policy target supports EQUALS, NOT_EQUALS, EXISTS, NOT_EXISTS, CONTAINS or IN")
    boolean isTargetableOperator() {
        return AttributeOperator.targetable(operator);
    }
}
