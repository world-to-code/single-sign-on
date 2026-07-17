package com.example.sso.session.policy;

import com.example.sso.metadata.AttributeOperator;
import com.example.sso.metadata.AttributePredicate;
import com.example.sso.metadata.AttributePredicateGroup;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A session-policy attribute target is an AND of conditions. Guards that a target needs at least one condition,
 * that an invalid nested condition invalidates it, and that the {@code SessionPolicyRequest} mapping carries the
 * conjunction as one {@link AttributePredicateGroup} (and a missing list maps to no groups).
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
                new AttributeTargetRequest(List.of(new AttributeConditionRequest("has space", null, "eng")));
        assertThat(validator.validate(target)).isNotEmpty();
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
    void sessionPolicyRequestMapsAssignedAttributeGroupsIntoTheSpecAndUpdate() {
        SessionPolicyRequest request = new SessionPolicyRequest("P", 5, true, 480, 30, 15, "TOTP", 2, "TOTP",
                false, 0, false, "Lax", List.of(), List.of(),
                List.of(new AttributeTargetRequest(List.of(
                        new AttributeConditionRequest("dept", AttributeOperator.EQUALS, "eng")))), List.of());

        AttributePredicateGroup expected = AttributePredicateGroup.of(AttributePredicate.equals("dept", "eng"));
        assertThat(request.toSpec().attributePredicates()).containsExactly(expected);
        assertThat(request.toUpdate().attributePredicates()).containsExactly(expected);
    }

    @Test
    void aMissingAttributeListMapsToNoGroups() {
        SessionPolicyRequest request = new SessionPolicyRequest("P", 5, true, 480, 30, 15, "TOTP", 2, "TOTP",
                false, 0, false, "Lax", List.of(), List.of(), null, List.of());

        assertThat(request.toSpec().attributePredicates()).isEmpty();
    }
}
