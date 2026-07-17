package com.example.sso.session.policy;

import com.example.sso.metadata.AttributeOperator;
import com.example.sso.metadata.AttributePredicate;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One condition of a session-policy attribute target: a bounded key, an operator, and a value required for the
 * value operators and forbidden for the key operators. Mirrors the metadata store's own validation and the
 * {@code authpolicy} twin; guards against the constraints being loosened.
 */
class AttributeConditionRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void aWellFormedConditionIsAccepted() {
        assertThat(validator.validate(new AttributeConditionRequest("dept", AttributeOperator.EQUALS, "engineering")))
                .isEmpty();
    }

    @Test
    void aMissingOperatorDefaultsToEquals() {
        assertThat(validator.validate(new AttributeConditionRequest("dept", null, "engineering"))).isEmpty();
        assertThat(new AttributeConditionRequest("dept", null, "engineering").toPredicate())
                .isEqualTo(AttributePredicate.equals("dept", "engineering"));
    }

    @Test
    void aBlankOrOversizedOrIllegalKeyIsRejected() {
        assertThat(validator.validate(new AttributeConditionRequest(" ", null, "eng"))).isNotEmpty();
        assertThat(validator.validate(new AttributeConditionRequest("a".repeat(65), null, "eng"))).isNotEmpty();
        assertThat(validator.validate(new AttributeConditionRequest("has space", null, "eng"))).isNotEmpty();
    }

    @Test
    void aValueOperatorRequiresANonBlankValue() {
        assertThat(validator.validate(new AttributeConditionRequest("dept", AttributeOperator.EQUALS, " ")))
                .isNotEmpty();
        assertThat(validator.validate(new AttributeConditionRequest("dept", AttributeOperator.NOT_EQUALS, null)))
                .isNotEmpty();
    }

    @Test
    void aKeyOperatorMustNotCarryAValue() {
        assertThat(validator.validate(new AttributeConditionRequest("dept", AttributeOperator.EXISTS, null))).isEmpty();
        assertThat(validator.validate(new AttributeConditionRequest("dept", AttributeOperator.NOT_EXISTS, null)))
                .isEmpty();
        assertThat(validator.validate(new AttributeConditionRequest("dept", AttributeOperator.EXISTS, "eng")))
                .isNotEmpty();
    }

    @Test
    void containsIsAcceptedForAPolicyTarget() {
        assertThat(validator.validate(new AttributeConditionRequest("dept", AttributeOperator.CONTAINS, "eng")))
                .isEmpty();
        assertThat(new AttributeConditionRequest("dept", AttributeOperator.CONTAINS, "eng").toPredicate())
                .isEqualTo(new AttributePredicate("dept", AttributeOperator.CONTAINS, "eng"));
    }

    @Test
    void inTargetsAValueListNotAScalar() {
        // IN is targetable but carries a non-empty value LIST, never a scalar value; a scalar op must carry no list.
        assertThat(validator.validate(new AttributeConditionRequest("dept", AttributeOperator.IN, "eng")))
                .isNotEmpty();
        assertThat(validator.validate(new AttributeConditionRequest("dept", AttributeOperator.IN, null, List.of())))
                .isNotEmpty();
        assertThat(validator.validate(
                new AttributeConditionRequest("dept", AttributeOperator.EQUALS, "eng", List.of("eng"))))
                .isNotEmpty();
        assertThat(validator.validate(
                new AttributeConditionRequest("dept", AttributeOperator.IN, null, List.of("eng", "infra")))).isEmpty();
        assertThat(new AttributeConditionRequest("dept", AttributeOperator.IN, null, List.of("eng", "infra"))
                .toPredicate()).isEqualTo(AttributePredicate.in("dept", List.of("eng", "infra")));
    }

    @Test
    void toPredicateCarriesTheOperatorAndDropsAKeyOperatorsValue() {
        assertThat(new AttributeConditionRequest("dept", AttributeOperator.NOT_EQUALS, "sales").toPredicate())
                .isEqualTo(new AttributePredicate("dept", AttributeOperator.NOT_EQUALS, "sales"));
        assertThat(new AttributeConditionRequest("dept", AttributeOperator.EXISTS, null).toPredicate())
                .isEqualTo(new AttributePredicate("dept", AttributeOperator.EXISTS, null));
    }
}
