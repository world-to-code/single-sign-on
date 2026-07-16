package com.example.sso.admin.internal.mapping.api;

import com.example.sso.metadata.AttributeOperator;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One mapping-rule condition's bean-validation: a bounded identifier key, a positive operator (EQUALS/EXISTS/IN),
 * a scalar value present exactly for EQUALS, and a bounded non-empty value list exactly for IN. Guards against a
 * malformed condition reaching the service.
 */
class MappingConditionRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void aWellFormedConditionIsAccepted() {
        assertThat(validator.validate(new MappingConditionRequest("department", AttributeOperator.EQUALS, "eng")))
                .isEmpty();
    }

    @Test
    void aBlankOrIllegalOrOversizedKeyIsRejected() {
        assertThat(validator.validate(new MappingConditionRequest(" ", AttributeOperator.EQUALS, "eng"))).isNotEmpty();
        assertThat(validator.validate(new MappingConditionRequest("has space", AttributeOperator.EQUALS, "eng")))
                .isNotEmpty();
        assertThat(validator.validate(new MappingConditionRequest("a".repeat(65), AttributeOperator.EQUALS, "eng")))
                .isNotEmpty();
        assertThat(validator.validate(new MappingConditionRequest("dept", AttributeOperator.EQUALS, "v".repeat(256))))
                .isNotEmpty();
    }

    @Test
    void aNegativeOperatorIsRejected() {
        assertThat(validator.validate(new MappingConditionRequest("dept", AttributeOperator.NOT_EQUALS, "sales")))
                .isNotEmpty();
        assertThat(validator.validate(new MappingConditionRequest("dept", AttributeOperator.NOT_EXISTS, null)))
                .isNotEmpty();
    }

    @Test
    void valueMustBePresentForEqualsAndAbsentForExists() {
        assertThat(validator.validate(new MappingConditionRequest("dept", AttributeOperator.EQUALS, " "))).isNotEmpty();
        assertThat(validator.validate(new MappingConditionRequest("dept", AttributeOperator.EXISTS, null))).isEmpty();
        assertThat(validator.validate(new MappingConditionRequest("dept", AttributeOperator.EXISTS, "eng")))
                .isNotEmpty(); // a value on EXISTS is inconsistent
    }

    @Test
    void anInConditionRequiresANonEmptyValueListAndNoScalarValue() {
        assertThat(validator.validate(new MappingConditionRequest("dept", AttributeOperator.IN, null,
                List.of("eng", "infra")))).isEmpty();
        assertThat(validator.validate(new MappingConditionRequest("dept", AttributeOperator.IN, null, List.of())))
                .isNotEmpty(); // empty list
        assertThat(validator.validate(new MappingConditionRequest("dept", AttributeOperator.IN, "eng",
                List.of("eng")))).isNotEmpty(); // a scalar value alongside IN is inconsistent
    }

    @Test
    void aBlankOrOversizedValueInTheListIsRejected() {
        assertThat(validator.validate(new MappingConditionRequest("dept", AttributeOperator.IN, null,
                List.of("eng", " ")))).isNotEmpty();                                   // blank element
        assertThat(validator.validate(new MappingConditionRequest("dept", AttributeOperator.IN, null,
                List.of("v".repeat(256))))).isNotEmpty();                              // element > 255
    }

    @Test
    void aNonInConditionMustNotCarryAValueList() {
        assertThat(validator.validate(new MappingConditionRequest("dept", AttributeOperator.EQUALS, "eng",
                List.of("infra")))).isNotEmpty();
    }

    @Test
    void anInRequestMapsToAConditionCarryingTheList() {
        assertThat(new MappingConditionRequest("dept", AttributeOperator.IN, null, List.of("eng", "infra"))
                .toCondition())
                .satisfies(c -> assertThat(c.attrOp()).isEqualTo(AttributeOperator.IN))
                .satisfies(c -> assertThat(c.attrValues()).containsExactly("eng", "infra"))
                .satisfies(c -> assertThat(c.attrValue()).isNull());
    }

    @Test
    void aContainsConditionRequiresAScalarValueAndMapsToACondition() {
        assertThat(validator.validate(new MappingConditionRequest("dept", AttributeOperator.CONTAINS, "eng")))
                .isEmpty();
        assertThat(validator.validate(new MappingConditionRequest("dept", AttributeOperator.CONTAINS, null)))
                .isNotEmpty();
        assertThat(new MappingConditionRequest("dept", AttributeOperator.CONTAINS, "eng").toCondition())
                .satisfies(c -> assertThat(c.attrOp()).isEqualTo(AttributeOperator.CONTAINS))
                .satisfies(c -> assertThat(c.attrValue()).isEqualTo("eng")); // scalar carried through, not dropped
    }

    @Test
    void aMissingOperatorDefaultsToEqualsAndAnExistsDropsTheValue() {
        assertThat(new MappingConditionRequest("dept", null, "eng").toCondition())
                .satisfies(c -> assertThat(c.attrOp()).isEqualTo(AttributeOperator.EQUALS))
                .satisfies(c -> assertThat(c.attrValue()).isEqualTo("eng"));
        assertThat(new MappingConditionRequest("dept", AttributeOperator.EXISTS, null).toCondition())
                .satisfies(c -> assertThat(c.attrOp()).isEqualTo(AttributeOperator.EXISTS))
                .satisfies(c -> assertThat(c.attrValue()).isNull());
    }
}
