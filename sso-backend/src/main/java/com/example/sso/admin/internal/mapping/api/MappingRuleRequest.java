package com.example.sso.admin.internal.mapping.api;

import com.example.sso.mapping.MappingRuleSpec;
import com.example.sso.mapping.MappingTargetKind;
import com.example.sso.metadata.AttributeOperator;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Create/update request for a mapping rule: users a predicate ({@code attrKey <attrOp> attrValue}) matches are
 * assigned to {@code targetId} (a group or role, per {@code thenKind}). A rule supports only the positive
 * operators EQUALS (value required) and EXISTS (value omitted); a missing operator defaults to EQUALS. Key/value
 * bounds mirror the metadata store.
 */
public record MappingRuleRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]*") String attrKey,
        AttributeOperator attrOp,
        @Size(max = 255) String attrValue,
        @NotNull MappingTargetKind thenKind,
        @NotNull UUID targetId) {

    public MappingRuleSpec toSpec() {
        AttributeOperator op = operator();
        return new MappingRuleSpec(attrKey, op, op == AttributeOperator.EQUALS ? attrValue : null, thenKind,
                targetId);
    }

    private AttributeOperator operator() {
        return AttributeOperator.orDefault(attrOp);
    }

    @AssertTrue(message = "a mapping rule supports only EQUALS or EXISTS")
    boolean isMappableOperator() {
        AttributeOperator op = operator();
        return op == AttributeOperator.EQUALS || op == AttributeOperator.EXISTS;
    }

    @AssertTrue(message = "value is required for EQUALS and must be empty for EXISTS")
    boolean isValueConsistentWithOperator() {
        return AttributeOperator.valueConsistent(attrOp, attrValue);
    }
}
