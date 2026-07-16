package com.example.sso.metadata;

import java.util.List;
import java.util.Locale;

/**
 * A predicate over an entity's metadata: the entity's attributes are tested against {@code key} with an
 * {@link AttributeOperator}. The query-side counterpart of a stored {@link Attribute}, used to target a cohort
 * (e.g. "users where {@code department = engineering}", "users with no {@code department}", or "users where
 * {@code department} is one of a list"). Value operators carry a single {@code value}; {@code IN} carries a
 * non-empty {@code values} list; key operators (EXISTS/NOT_EXISTS) carry neither. Pattern operators are a later
 * extension.
 */
public record AttributePredicate(String key, AttributeOperator operator, String value, List<String> values) {

    public AttributePredicate {
        values = values == null ? List.of() : List.copyOf(values);
        boolean hasValue = value != null;
        boolean hasValues = !values.isEmpty();
        if (operator.requiresValue() != hasValue) {
            throw new IllegalArgumentException("operator " + operator
                    + (operator.requiresValue() ? " requires a value" : " must not carry a value"));
        }
        if (operator.requiresValueList() != hasValues) {
            throw new IllegalArgumentException("operator " + operator + (operator.requiresValueList()
                    ? " requires a non-empty value list" : " must not carry a value list"));
        }
    }

    /** A single-value or value-less predicate (EQUALS/NOT_EQUALS/EXISTS/NOT_EXISTS) — no value list. */
    public AttributePredicate(String key, AttributeOperator operator, String value) {
        this(key, operator, value, List.of());
    }

    /** The common equality predicate: the entity carries {@code key = value}. */
    public static AttributePredicate equals(String key, String value) {
        return new AttributePredicate(key, AttributeOperator.EQUALS, value);
    }

    /** The value-list predicate: the entity carries {@code key} with a value in {@code values} (an OR over them). */
    public static AttributePredicate in(String key, List<String> values) {
        return new AttributePredicate(key, AttributeOperator.IN, null, values);
    }

    /** Whether the given attributes satisfy this predicate under its operator. */
    public boolean matches(Iterable<Attribute> attributes) {
        return switch (operator) {
            case EQUALS -> hasKeyValue(attributes);
            case NOT_EQUALS -> !hasKeyValue(attributes);
            case EXISTS -> hasKey(attributes);
            case NOT_EXISTS -> !hasKey(attributes);
            case IN -> hasKeyValueIn(attributes);
            case CONTAINS -> hasKeyValueContaining(attributes);
        };
    }

    // Case-insensitive substring. This in-memory fold (Locale.ROOT) agrees with the cohort's SQL ILIKE for ASCII
    // attribute values (the norm); a locale-sensitive char could diverge, but the cohort sweep is authoritative
    // and re-reconciles, so at worst a membership briefly flaps rather than sticking wrong.
    private boolean hasKeyValueContaining(Iterable<Attribute> attributes) {
        String needle = value.toLowerCase(Locale.ROOT);
        for (Attribute attribute : attributes) {
            if (attribute.key().equals(key) && attribute.value().toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasKeyValue(Iterable<Attribute> attributes) {
        for (Attribute attribute : attributes) {
            if (attribute.key().equals(key) && attribute.value().equals(value)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasKeyValueIn(Iterable<Attribute> attributes) {
        for (Attribute attribute : attributes) {
            if (attribute.key().equals(key) && values.contains(attribute.value())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasKey(Iterable<Attribute> attributes) {
        for (Attribute attribute : attributes) {
            if (attribute.key().equals(key)) {
                return true;
            }
        }
        return false;
    }
}
