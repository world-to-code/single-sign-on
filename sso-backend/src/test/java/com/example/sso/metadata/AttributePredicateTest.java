package com.example.sso.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The shared attribute matcher across the four operators. Each case is evaluated against a subject carrying
 * {@code department = engineering}, so the three worlds a predicate must distinguish are: the key present with
 * the SAME value, present with a DIFFERENT value, and absent entirely.
 */
class AttributePredicateTest {

    private static final List<Attribute> HAS_ENG = List.of(new Attribute("department", "engineering"));
    private static final List<Attribute> HAS_SALES = List.of(new Attribute("department", "sales"));
    private static final List<Attribute> NO_DEPARTMENT = List.of(new Attribute("location", "berlin"));

    @Test
    void equalsMatchesOnlyTheSameKeyAndValue() {
        AttributePredicate eng = AttributePredicate.equals("department", "engineering");
        assertThat(eng.matches(HAS_ENG)).isTrue();
        assertThat(eng.matches(HAS_SALES)).isFalse();
        assertThat(eng.matches(NO_DEPARTMENT)).isFalse();
    }

    @Test
    void notEqualsIsTheStrictNegationOfEquals_absentKeyIncluded() {
        AttributePredicate notEng = new AttributePredicate("department", AttributeOperator.NOT_EQUALS, "engineering");
        assertThat(notEng.matches(HAS_ENG)).isFalse();
        assertThat(notEng.matches(HAS_SALES)).isTrue();      // different value → not equal
        assertThat(notEng.matches(NO_DEPARTMENT)).isTrue();  // absent key → not equal (deliberate)
    }

    @Test
    void existsMatchesAnyValueOfTheKeyAndIgnoresValue() {
        AttributePredicate hasDept = new AttributePredicate("department", AttributeOperator.EXISTS, null);
        assertThat(hasDept.matches(HAS_ENG)).isTrue();
        assertThat(hasDept.matches(HAS_SALES)).isTrue();
        assertThat(hasDept.matches(NO_DEPARTMENT)).isFalse();
    }

    @Test
    void notExistsMatchesOnlyTheAbsenceOfTheKey() {
        AttributePredicate noDept = new AttributePredicate("department", AttributeOperator.NOT_EXISTS, null);
        assertThat(noDept.matches(HAS_ENG)).isFalse();
        assertThat(noDept.matches(HAS_SALES)).isFalse();
        assertThat(noDept.matches(NO_DEPARTMENT)).isTrue();
    }

    @Test
    void inMatchesAnyValueInTheListAndNothingOutsideIt() {
        AttributePredicate deptInEngOrSales = AttributePredicate.in("department", List.of("engineering", "sales"));
        assertThat(deptInEngOrSales.matches(HAS_ENG)).isTrue();
        assertThat(deptInEngOrSales.matches(HAS_SALES)).isTrue();
        assertThat(deptInEngOrSales.matches(List.of(new Attribute("department", "legal")))).isFalse();
        assertThat(deptInEngOrSales.matches(NO_DEPARTMENT)).isFalse();
    }

    @Test
    void inRequiresANonEmptyListAndNoScalarValue() {
        assertThatThrownBy(() -> new AttributePredicate("department", AttributeOperator.IN, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AttributePredicate("department", AttributeOperator.IN, "eng", List.of("eng")))
                .isInstanceOf(IllegalArgumentException.class); // IN carries the list, not a scalar value
        assertThatThrownBy(() -> new AttributePredicate("department", AttributeOperator.EQUALS, "eng",
                List.of("eng"))).isInstanceOf(IllegalArgumentException.class); // a scalar op must not carry a list
    }

    @Test
    void emptyAttributesSatisfyOnlyTheNegativeOperators() {
        List<Attribute> none = List.of();
        assertThat(AttributePredicate.equals("department", "engineering").matches(none)).isFalse();
        assertThat(new AttributePredicate("department", AttributeOperator.EXISTS, null).matches(none)).isFalse();
        assertThat(new AttributePredicate("department", AttributeOperator.NOT_EQUALS, "engineering").matches(none))
                .isTrue();
        assertThat(new AttributePredicate("department", AttributeOperator.NOT_EXISTS, null).matches(none)).isTrue();
    }

    @Test
    void aValueOperatorRequiresAValueAndAKeyOperatorRejectsOne() {
        assertThatThrownBy(() -> new AttributePredicate("department", AttributeOperator.EQUALS, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AttributePredicate("department", AttributeOperator.EXISTS, "engineering"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void predicatesAreValueObjects_operatorDistinguishesThem() {
        assertThat(AttributePredicate.equals("department", "engineering"))
                .isEqualTo(new AttributePredicate("department", AttributeOperator.EQUALS, "engineering"))
                .isNotEqualTo(new AttributePredicate("department", AttributeOperator.NOT_EQUALS, "engineering"));
    }
}
