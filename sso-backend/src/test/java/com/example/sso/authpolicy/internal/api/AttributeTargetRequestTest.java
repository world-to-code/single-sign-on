package com.example.sso.authpolicy.internal.api;

import com.example.sso.metadata.AttributeOperator;
import com.example.sso.metadata.AttributePredicate;
import com.example.sso.metadata.AttributePredicateGroup;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An attribute target is an AND of conditions ({@code dept = eng AND level = senior}). Guards that a target needs
 * at least one condition, that each condition is validated (invalid conditions bubble up), and that the mapping
 * into the create/update command carries the conjunction as one {@link AttributePredicateGroup}.
 */
class AttributeTargetRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void aTargetNeedsAtLeastOneCondition() {
        assertThat(validator.validate(new AttributeTargetRequest(List.of()))).isNotEmpty();
    }

    @Test
    void anInvalidConditionInvalidatesTheTarget() {
        AttributeTargetRequest target =
                new AttributeTargetRequest(List.of(new AttributeConditionRequest(" ", null, "eng")));
        assertThat(validator.validate(target)).isNotEmpty(); // blank key on the nested condition bubbles up
    }

    @Test
    void toGroupCarriesEveryConditionAsAConjunction() {
        AttributeTargetRequest target = new AttributeTargetRequest(List.of(
                new AttributeConditionRequest("dept", AttributeOperator.EQUALS, "eng"),
                new AttributeConditionRequest("clearance", AttributeOperator.EXISTS, null)));
        assertThat(target.toGroup()).isEqualTo(new AttributePredicateGroup(List.of(
                AttributePredicate.equals("dept", "eng"),
                new AttributePredicate("clearance", AttributeOperator.EXISTS, null))));
    }

    @Test
    void policyRequestMapsAssignedAttributeGroupsIntoTheSpecAndUpdate() {
        PolicyRequest request = new PolicyRequest("P", 5, true, true, true, List.of(List.of("PASSWORD")),
                List.of(), List.of(),
                List.of(new AttributeTargetRequest(List.of(
                        new AttributeConditionRequest("dept", AttributeOperator.EQUALS, "eng"),
                        new AttributeConditionRequest("level", AttributeOperator.EQUALS, "senior")))), 15);

        AttributePredicateGroup expected = new AttributePredicateGroup(List.of(
                AttributePredicate.equals("dept", "eng"), AttributePredicate.equals("level", "senior")));
        assertThat(request.toSpec().attributePredicates()).containsExactly(expected);
        assertThat(request.toUpdate().attributePredicates()).containsExactly(expected);
    }
}
