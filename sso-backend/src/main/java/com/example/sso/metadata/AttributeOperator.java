package com.example.sso.metadata;

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
    NOT_EXISTS;

    /** Whether this operator compares against a value (so a predicate/row must carry one). */
    public boolean requiresValue() {
        return this == EQUALS || this == NOT_EQUALS;
    }

    /** A null operator (a request that omits it) means EQUALS — the backward-compatible default. */
    public static AttributeOperator orDefault(AttributeOperator operator) {
        return operator == null ? EQUALS : operator;
    }

    /**
     * Whether a request's (possibly null) operator and value are shape-consistent: a value is present exactly
     * for a value operator. Shared by the predicate-targeting request DTOs so the rule cannot drift between them.
     */
    public static boolean valueConsistent(AttributeOperator operator, String value) {
        return (value != null && !value.isBlank()) == orDefault(operator).requiresValue();
    }
}
