package com.example.sso.session.policy;

import com.example.sso.metadata.AttributePredicate;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The attribute-predicate targeting request: a bounded key (identifier charset) and value, mirroring the
 * metadata store's own validation, plus the {@code SessionPolicyRequest} mapping that carries predicates into
 * the create/update command. Guards against the constraints being loosened or the mapping silently dropping
 * predicates.
 */
class AttributeTargetRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void aWellFormedPredicateIsAccepted() {
        assertThat(validator.validate(new AttributeTargetRequest("dept", "engineering"))).isEmpty();
    }

    @Test
    void aBlankOrOversizedOrIllegalKeyIsRejected() {
        assertThat(validator.validate(new AttributeTargetRequest(" ", "eng"))).isNotEmpty();     // blank
        assertThat(validator.validate(new AttributeTargetRequest("a".repeat(65), "eng"))).isNotEmpty(); // > 64
        assertThat(validator.validate(new AttributeTargetRequest("has space", "eng"))).isNotEmpty(); // pattern
        assertThat(validator.validate(new AttributeTargetRequest("dept", " "))).isNotEmpty();    // blank value
    }

    @Test
    void toPredicateCarriesTheKeyAndValue() {
        assertThat(new AttributeTargetRequest("dept", "eng").toPredicate())
                .isEqualTo(new AttributePredicate("dept", "eng"));
    }

    @Test
    void sessionPolicyRequestMapsAssignedAttributesIntoTheSpecAndUpdate() {
        SessionPolicyRequest request = new SessionPolicyRequest("P", 5, true, 480, 30, 15, "TOTP", 2, "TOTP",
                false, 0, false, "Lax", List.of(), List.of(),
                List.of(new AttributeTargetRequest("dept", "eng")), List.of());

        assertThat(request.toSpec().attributePredicates()).containsExactly(new AttributePredicate("dept", "eng"));
        assertThat(request.toUpdate().attributePredicates()).containsExactly(new AttributePredicate("dept", "eng"));
    }

    @Test
    void aMissingAttributeListMapsToNoPredicates() {
        SessionPolicyRequest request = new SessionPolicyRequest("P", 5, true, 480, 30, 15, "TOTP", 2, "TOTP",
                false, 0, false, "Lax", List.of(), List.of(), null, List.of());

        assertThat(request.toSpec().attributePredicates()).isEmpty();
    }
}
