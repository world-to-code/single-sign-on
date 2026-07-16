package com.example.sso.session.policy;

import com.example.sso.metadata.AttributeOperator;
import com.example.sso.metadata.AttributePredicate;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The attribute-predicate targeting request: a bounded key (identifier charset), an operator, and a value that
 * is required for the value operators and forbidden for the key operators. Mirrors the metadata store's own
 * validation, plus the {@code SessionPolicyRequest} mapping that carries predicates into the create/update
 * command. Guards against the constraints being loosened or the mapping silently dropping predicates.
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
    void aBlankOrOversizedOrIllegalKeyIsRejected() {
        assertThat(validator.validate(new AttributeTargetRequest(" ", null, "eng"))).isNotEmpty();     // blank
        assertThat(validator.validate(new AttributeTargetRequest("a".repeat(65), null, "eng"))).isNotEmpty(); // > 64
        assertThat(validator.validate(new AttributeTargetRequest("has space", null, "eng"))).isNotEmpty(); // pattern
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
                .isNotEmpty(); // a value on a key operator is inconsistent
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
    void sessionPolicyRequestMapsAssignedAttributesIntoTheSpecAndUpdate() {
        SessionPolicyRequest request = new SessionPolicyRequest("P", 5, true, 480, 30, 15, "TOTP", 2, "TOTP",
                false, 0, false, "Lax", List.of(), List.of(),
                List.of(new AttributeTargetRequest("dept", AttributeOperator.EQUALS, "eng")), List.of());

        assertThat(request.toSpec().attributePredicates()).containsExactly(AttributePredicate.equals("dept", "eng"));
        assertThat(request.toUpdate().attributePredicates()).containsExactly(AttributePredicate.equals("dept", "eng"));
    }

    @Test
    void aMissingAttributeListMapsToNoPredicates() {
        SessionPolicyRequest request = new SessionPolicyRequest("P", 5, true, 480, 30, 15, "TOTP", 2, "TOTP",
                false, 0, false, "Lax", List.of(), List.of(), null, List.of());

        assertThat(request.toSpec().attributePredicates()).isEmpty();
    }
}
