package com.example.sso.admin.internal.mapping.api;

import com.example.sso.mapping.MappingTargetKind;
import com.example.sso.metadata.AttributeOperator;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mapping-rule request's bean-validation at the RULE level: at least one condition, a kind and a target, and
 * that a malformed condition in the list fails the whole request (the {@code @Valid} cascade). Per-condition
 * rules are covered by {@link MappingConditionRequestTest}.
 */
class MappingRuleRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private MappingConditionRequest cond(String key, AttributeOperator op, String value) {
        return new MappingConditionRequest(key, op, value);
    }

    private MappingRuleRequest request(List<MappingConditionRequest> conditions, MappingTargetKind kind, UUID target) {
        return new MappingRuleRequest(conditions, kind, target);
    }

    @Test
    void aWellFormedRequestIsAccepted() {
        assertThat(validator.validate(request(List.of(cond("department", AttributeOperator.EQUALS, "engineering")),
                MappingTargetKind.ROLE, UUID.randomUUID()))).isEmpty();
    }

    @Test
    void anEmptyConditionListIsRejected() {
        assertThat(validator.validate(request(List.of(), MappingTargetKind.GROUP, UUID.randomUUID()))).isNotEmpty();
    }

    @Test
    void aMalformedConditionFailsTheWholeRequest() {
        // @Valid cascades: a NOT_EQUALS (un-mappable) or an EQUALS with no value inside the list invalidates the rule.
        UUID g = UUID.randomUUID();
        assertThat(validator.validate(request(List.of(cond("dept", AttributeOperator.NOT_EQUALS, "sales")),
                MappingTargetKind.GROUP, g))).isNotEmpty();
        assertThat(validator.validate(request(List.of(cond("dept", AttributeOperator.EQUALS, " ")),
                MappingTargetKind.GROUP, g))).isNotEmpty();
    }

    @Test
    void aMissingKindOrTargetIsRejected() {
        List<MappingConditionRequest> ok = List.of(cond("dept", AttributeOperator.EQUALS, "eng"));
        assertThat(validator.validate(request(ok, null, UUID.randomUUID()))).isNotEmpty();
        assertThat(validator.validate(request(ok, MappingTargetKind.GROUP, null))).isNotEmpty();
    }

    @Test
    void toSpecCarriesEveryConditionKindAndTarget() {
        UUID target = UUID.randomUUID();
        MappingRuleRequest request = request(List.of(
                cond("dept", AttributeOperator.EQUALS, "eng"),
                cond("clearance", AttributeOperator.EXISTS, null)), MappingTargetKind.ROLE, target);
        assertThat(request.toSpec())
                .satisfies(s -> assertThat(s.conditions()).hasSize(2))
                .satisfies(s -> assertThat(s.thenKind()).isEqualTo(MappingTargetKind.ROLE))
                .satisfies(s -> assertThat(s.targetId()).isEqualTo(target));
        assertThat(request.toSpec().conditions().get(1).attrValue()).isNull(); // EXISTS drops its value
    }
}
