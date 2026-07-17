package com.example.sso.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The AND conjunction of attribute predicates. A group matches only when EVERY condition matches; its identity is
 * order-independent (canonically sorted) so a {@code Set} dedups groups and the write-path diff compares them by
 * value regardless of the order the admin listed the conditions.
 */
class AttributePredicateGroupTest {

    private static final AttributePredicate DEPT_ENG = AttributePredicate.equals("dept", "eng");
    private static final AttributePredicate LEVEL_SENIOR = AttributePredicate.equals("level", "senior");
    private static final AttributePredicate HAS_CLEARANCE =
            new AttributePredicate("clearance", AttributeOperator.EXISTS, null);

    @Test
    void matchesOnlyWhenEveryConditionMatches() {
        AttributePredicateGroup group = new AttributePredicateGroup(List.of(DEPT_ENG, LEVEL_SENIOR));
        assertThat(group.matches(List.of(new Attribute("dept", "eng"), new Attribute("level", "senior")))).isTrue();
        assertThat(group.matches(List.of(new Attribute("dept", "eng")))).isFalse();            // one condition unmet
        assertThat(group.matches(List.of(new Attribute("level", "senior")))).isFalse();
        assertThat(group.matches(List.of())).isFalse();
    }

    @Test
    void aGroupWithAnInConditionMatchesAnyListedValue() {
        AttributePredicateGroup group = new AttributePredicateGroup(List.of(
                AttributePredicate.in("dept", List.of("eng", "infra")), LEVEL_SENIOR));
        assertThat(group.matches(List.of(new Attribute("dept", "infra"), new Attribute("level", "senior")))).isTrue();
        assertThat(group.matches(List.of(new Attribute("dept", "sales"), new Attribute("level", "senior")))).isFalse();
        assertThat(group.matches(List.of(new Attribute("level", "senior")))).isFalse(); // dept key absent → IN fails
    }

    @Test
    void aSingleConditionGroupMatchesLikeThePredicate() {
        AttributePredicateGroup group = AttributePredicateGroup.of(HAS_CLEARANCE);
        assertThat(group.matches(List.of(new Attribute("clearance", "ts")))).isTrue();
        assertThat(group.matches(List.of(new Attribute("dept", "eng")))).isFalse();
    }

    @Test
    void conditionOrderDoesNotAffectIdentity() {
        AttributePredicateGroup a = new AttributePredicateGroup(List.of(DEPT_ENG, LEVEL_SENIOR, HAS_CLEARANCE));
        AttributePredicateGroup b = new AttributePredicateGroup(List.of(HAS_CLEARANCE, LEVEL_SENIOR, DEPT_ENG));
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a.conditions()).isEqualTo(b.conditions());                   // both canonically sorted
        assertThat(new HashSet<>(List.of(a, b))).hasSize(1);                   // dedups
    }

    @Test
    void groupsWithDifferentConditionsAreDistinct() {
        AttributePredicateGroup engAndSenior = new AttributePredicateGroup(List.of(DEPT_ENG, LEVEL_SENIOR));
        AttributePredicateGroup engOnly = AttributePredicateGroup.of(DEPT_ENG);
        AttributePredicateGroup engAndClearance = new AttributePredicateGroup(List.of(DEPT_ENG, HAS_CLEARANCE));
        assertThat(engAndSenior).isNotEqualTo(engOnly).isNotEqualTo(engAndClearance);
    }

    @Test
    void anEmptyOrNullGroupIsRejected() {
        assertThatThrownBy(() -> new AttributePredicateGroup(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AttributePredicateGroup(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theCanonicalConditionListIsImmutable() {
        AttributePredicateGroup group = new AttributePredicateGroup(List.of(DEPT_ENG, LEVEL_SENIOR));
        assertThatThrownBy(() -> group.conditions().add(HAS_CLEARANCE))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
