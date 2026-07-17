package com.example.sso.authpolicy.internal.api;

import com.example.sso.metadata.AttributeOperator;
import com.example.sso.metadata.AttributePredicate;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One condition of an auth-policy attribute target — the twin of the session-policy condition. A bounded key, an
 * operator, and a value required for value operators and forbidden for key operators. Kept in lockstep with the
 * session twin so an operator regression on the login-policy side cannot slip through untested.
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
    void aBlankOrIllegalOrOversizedKeyIsRejected() {
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
    void inIsRejectedButContainsIsAcceptedForAPolicyTarget() {
        // IN is mapping-only (it needs value-list storage the binding lacks) — rejected at the edge (400), not
        // 500 on the CHECK. CONTAINS is a value operator the resolver matches in memory, so a policy may use it.
        assertThat(validator.validate(new AttributeConditionRequest("dept", AttributeOperator.IN, "eng")))
                .isNotEmpty();
        assertThat(validator.validate(new AttributeConditionRequest("dept", AttributeOperator.CONTAINS, "eng")))
                .isEmpty();
        assertThat(new AttributeConditionRequest("dept", AttributeOperator.CONTAINS, "eng").toPredicate())
                .isEqualTo(new AttributePredicate("dept", AttributeOperator.CONTAINS, "eng"));
    }

    @Test
    void toPredicateCarriesTheOperatorAndDropsAKeyOperatorsValue() {
        assertThat(new AttributeConditionRequest("dept", AttributeOperator.NOT_EQUALS, "sales").toPredicate())
                .isEqualTo(new AttributePredicate("dept", AttributeOperator.NOT_EQUALS, "sales"));
        assertThat(new AttributeConditionRequest("dept", AttributeOperator.EXISTS, null).toPredicate())
                .isEqualTo(new AttributePredicate("dept", AttributeOperator.EXISTS, null));
    }
}
