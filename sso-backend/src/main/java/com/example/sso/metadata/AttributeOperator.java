package com.example.sso.metadata;

import java.util.Collection;

/**
 * How an {@link AttributePredicate} compares a stored attribute. Value operators ({@link #EQUALS},
 * {@link #NOT_EQUALS}) test the key against a specific value; key operators ({@link #EXISTS},
 * {@link #NOT_EXISTS}) only test whether the key is present, so they carry no value.
 */
public enum AttributeOperator {

    /** The entity carries {@code key = value}. */
    EQUALS,
    /** The strict negation of {@link #EQUALS}: the key is absent, or present with a different value. */
    NOT_EQUALS,
    /** The entity carries the key with any value. */
    EXISTS,
    /** The entity carries no attribute for the key. */
    NOT_EXISTS,
    /** The entity carries the key with a value in a given list (an OR over values). */
    IN,
    /** The entity carries the key with a value that CONTAINS the given substring (case-insensitive). */
    CONTAINS;

    /** Whether this operator compares against a single value (so a predicate/row must carry one). */
    public boolean requiresValue() {
        return this == EQUALS || this == NOT_EQUALS || this == CONTAINS;
    }

    /** Whether this operator compares against a list of values (so a predicate/row must carry a non-empty one). */
    public boolean requiresValueList() {
        return this == IN;
    }

    /** A null operator (a request that omits it) means EQUALS — the backward-compatible default. */
    public static AttributeOperator orDefault(AttributeOperator operator) {
        return operator == null ? EQUALS : operator;
    }

    /** Whether a (possibly null) operator may drive an auto-mapping rule: the POSITIVE, index-able operators only
     *  (EQUALS, EXISTS, IN, CONTAINS — each an indexed lookup/union/trigram scan over entity_attribute; a NOT_*
     *  cohort is "everyone without X", unbounded and un-indexable). One home for the whitelist. */
    public static boolean mappable(AttributeOperator operator) {
        AttributeOperator op = orDefault(operator);
        return op == EQUALS || op == EXISTS || op == IN || op == CONTAINS;
    }

    /** Whether a (possibly null) operator may TARGET a policy binding — the operators the resolver matches in
     *  memory (EQUALS, NOT_EQUALS, EXISTS, NOT_EXISTS, CONTAINS). The value-LIST operator IN is mapping-only, as it
     *  needs list storage the policy binding lacks. An explicit allow-list (like {@link #mappable}, mirroring the
     *  V99/V104 CHECK) so a future operator is denied by default until deliberately admitted. */
    public static boolean targetable(AttributeOperator operator) {
        AttributeOperator op = orDefault(operator);
        return op == EQUALS || op == NOT_EQUALS || op == EXISTS || op == NOT_EXISTS || op == CONTAINS;
    }

    /**
     * Whether a request's (possibly null) operator and value are shape-consistent: a value is present exactly
     * for a value operator. Shared by the predicate-targeting request DTOs so the rule cannot drift between them.
     */
    public static boolean valueConsistent(AttributeOperator operator, String value) {
        return (value != null && !value.isBlank()) == orDefault(operator).requiresValue();
    }

    /** Whether a request's (possibly null) operator and value LIST are shape-consistent: a non-empty list is
     *  present exactly for a value-list operator (IN). Shared so the DTOs can't drift. */
    public static boolean valueListConsistent(AttributeOperator operator, Collection<String> values) {
        return (values != null && !values.isEmpty()) == orDefault(operator).requiresValueList();
    }
}
