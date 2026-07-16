package com.example.sso.metadata;

/**
 * A predicate over an entity's metadata: the entity's attributes are tested against {@code key} with an
 * {@link AttributeOperator}. The query-side counterpart of a stored {@link Attribute}, used to target a cohort
 * (e.g. a policy binding whose subjects are "users where {@code department = engineering}", or "users that have
 * no {@code department} at all"). Value operators carry a {@code value}; key operators (EXISTS/NOT_EXISTS) carry
 * {@code null}. Value-list (IN) and pattern operators are a later extension.
 */
public record AttributePredicate(String key, AttributeOperator operator, String value) {

    public AttributePredicate {
        if (operator.requiresValue() == (value == null)) {
            throw new IllegalArgumentException("operator " + operator
                    + (operator.requiresValue() ? " requires a value" : " must not carry a value"));
        }
    }

    /** The common equality predicate: the entity carries {@code key = value}. */
    public static AttributePredicate equals(String key, String value) {
        return new AttributePredicate(key, AttributeOperator.EQUALS, value);
    }

    /** Whether the given attributes satisfy this predicate under its operator. */
    public boolean matches(Iterable<Attribute> attributes) {
        return switch (operator) {
            case EQUALS -> hasKeyValue(attributes);
            case NOT_EQUALS -> !hasKeyValue(attributes);
            case EXISTS -> hasKey(attributes);
            case NOT_EXISTS -> !hasKey(attributes);
        };
    }

    private boolean hasKeyValue(Iterable<Attribute> attributes) {
        for (Attribute attribute : attributes) {
            if (attribute.key().equals(key) && attribute.value().equals(value)) {
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
