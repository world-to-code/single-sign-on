package com.example.sso.authpolicy.internal.api;

import com.example.sso.metadata.AttributeOperator;
import com.example.sso.metadata.AttributePredicate;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The auth-policy attribute-predicate targeting request — the twin of the session-policy request. A bounded key,
 * an operator, and a value required for value operators and forbidden for key operators, plus the
 * {@code PolicyRequest} mapping that carries predicates into the create/update command. Kept in lockstep with the
 * session twin so an operator regression on the login-policy side cannot slip through untested.
 */
class AttributeTargetRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void aWellFormedPredicateIsAccepted() {
        assertThat(validator.validate(new AttributeTargetRequest("dept", AttributeOperator.EQUALS, "engineering")))
                .isEmpty();
    }

    @Test
    void aMissingOperatorDefaultsToEquals() {
        assertThat(validator.validate(new AttributeTargetRequest("dept", null, "engineering"))).isEmpty();
        assertThat(new AttributeTargetRequest("dept", null, "engineering").toPredicate())
                .isEqualTo(AttributePredicate.equals("dept", "engineering"));
    }

    @Test
    void aBlankOrIllegalOrOversizedKeyIsRejected() {
        assertThat(validator.validate(new AttributeTargetRequest(" ", null, "eng"))).isNotEmpty();
        assertThat(validator.validate(new AttributeTargetRequest("a".repeat(65), null, "eng"))).isNotEmpty();
        assertThat(validator.validate(new AttributeTargetRequest("has space", null, "eng"))).isNotEmpty();
    }

    @Test
    void aValueOperatorRequiresANonBlankValue() {
        assertThat(validator.validate(new AttributeTargetRequest("dept", AttributeOperator.EQUALS, " "))).isNotEmpty();
        assertThat(validator.validate(new AttributeTargetRequest("dept", AttributeOperator.NOT_EQUALS, null)))
                .isNotEmpty();
    }

    @Test
    void aKeyOperatorMustNotCarryAValue() {
        assertThat(validator.validate(new AttributeTargetRequest("dept", AttributeOperator.EXISTS, null))).isEmpty();
        assertThat(validator.validate(new AttributeTargetRequest("dept", AttributeOperator.NOT_EXISTS, null))).isEmpty();
        assertThat(validator.validate(new AttributeTargetRequest("dept", AttributeOperator.EXISTS, "eng")))
                .isNotEmpty();
    }

    @Test
    void aMappingOnlyOperatorIsRejectedForAPolicyTarget() {
        // IN and CONTAINS are mapping-only; a policy target must reject them at the edge (400), not 500 on the
        // policy_binding CHECK.
        assertThat(validator.validate(new AttributeTargetRequest("dept", AttributeOperator.IN, "eng"))).isNotEmpty();
        assertThat(validator.validate(new AttributeTargetRequest("dept", AttributeOperator.CONTAINS, "eng")))
                .isNotEmpty();
    }

    @Test
    void toPredicateCarriesTheOperatorAndDropsAKeyOperatorsValue() {
        assertThat(new AttributeTargetRequest("dept", AttributeOperator.NOT_EQUALS, "sales").toPredicate())
                .isEqualTo(new AttributePredicate("dept", AttributeOperator.NOT_EQUALS, "sales"));
        assertThat(new AttributeTargetRequest("dept", AttributeOperator.EXISTS, null).toPredicate())
                .isEqualTo(new AttributePredicate("dept", AttributeOperator.EXISTS, null));
    }

    @Test
    void policyRequestMapsAssignedAttributesIntoTheSpecAndUpdate() {
        PolicyRequest request = new PolicyRequest("P", 5, true, true, true, List.of(List.of("PASSWORD")),
                List.of(), List.of(),
                List.of(new AttributeTargetRequest("dept", AttributeOperator.EXISTS, null)), 15);

        AttributePredicate expected = new AttributePredicate("dept", AttributeOperator.EXISTS, null);
        assertThat(request.toSpec().attributePredicates()).containsExactly(expected);
        assertThat(request.toUpdate().attributePredicates()).containsExactly(expected);
    }
}
