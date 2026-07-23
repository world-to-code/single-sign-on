package com.example.sso.metadata;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The operator whitelists, pinned — most importantly the one a SECURITY guard depends on from three layers away.
 *
 * <p>{@code AttributeServiceImpl.requireRemovable} applies NO mapping-rule grant ceiling on removal, because a
 * mapping rule grants on the PRESENCE of a value and {@link AttributeOperator#mappable} admits only positive
 * operators — so removing a value can only ever retract a grant, never make one. If a negative operator is ever
 * made mappable, removing an attribute becomes a grant vector again and that guard must be re-added. This test
 * makes that coupling fail LOUD: flipping {@code mappable(NOT_*)} trips a red test right next to the change.
 */
class AttributeOperatorTest {

    @Test
    void onlyPositiveOperatorsAreMappable() {
        // If either of these flips to true, re-add the removal grant ceiling in AttributeServiceImpl.requireRemovable
        // (and its guard/adapter/service support) before shipping — removal would otherwise silently grant roles.
        assertThat(AttributeOperator.mappable(AttributeOperator.NOT_EXISTS)).isFalse();
        assertThat(AttributeOperator.mappable(AttributeOperator.NOT_EQUALS)).isFalse();

        assertThat(AttributeOperator.mappable(AttributeOperator.EQUALS)).isTrue();
        assertThat(AttributeOperator.mappable(AttributeOperator.EXISTS)).isTrue();
        assertThat(AttributeOperator.mappable(AttributeOperator.IN)).isTrue();
        assertThat(AttributeOperator.mappable(AttributeOperator.CONTAINS)).isTrue();
    }

    /** A null operator defaults to EQUALS, so it is mappable — the backward-compatible default a request may omit. */
    @Test
    void aNullOperatorDefaultsToMappableEquals() {
        assertThat(AttributeOperator.mappable(null)).isTrue();
    }

    /** Policy-binding targeting admits the negative operators too, since the resolver matches them in memory. */
    @Test
    void targetableAdmitsNegativeOperatorsUnlikeMappable() {
        assertThat(AttributeOperator.targetable(AttributeOperator.NOT_EXISTS)).isTrue();
        assertThat(AttributeOperator.targetable(AttributeOperator.NOT_EQUALS)).isTrue();
    }
}
