package com.example.sso.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.sso.metadata.AttributeOperator;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The mapping-condition value object's shape invariant — the last line of defence for a programmatic caller that
 * bypasses the request DTO: EQUALS needs a scalar value, EXISTS neither, IN a non-empty value list (and nothing
 * else). Guards against the compact-constructor checks being loosened.
 */
class MappingConditionTest {

    @Test
    void wellFormedConditionsAreAccepted() {
        assertThat(new MappingCondition("dept", AttributeOperator.EQUALS, "eng").attrValues()).isEmpty();
        assertThat(new MappingCondition("dept", AttributeOperator.EXISTS, null).attrValue()).isNull();
        assertThat(new MappingCondition("dept", AttributeOperator.IN, null, List.of("eng", "infra")).attrValues())
                .containsExactly("eng", "infra");
    }

    @Test
    void inRequiresANonEmptyListAndNoScalarValue() {
        assertThatThrownBy(() -> new MappingCondition("dept", AttributeOperator.IN, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MappingCondition("dept", AttributeOperator.IN, "eng", List.of("eng")))
                .isInstanceOf(IllegalArgumentException.class); // IN carries the list, not a scalar
    }

    @Test
    void aScalarOperatorMustNotCarryAListAndEqualsNeedsAValue() {
        assertThatThrownBy(() -> new MappingCondition("dept", AttributeOperator.EQUALS, "eng", List.of("infra")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MappingCondition("dept", AttributeOperator.EQUALS, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
