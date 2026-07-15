package com.example.sso.metadata;

/**
 * An equals-predicate over an entity's metadata: the entity carries {@code key = value}. The query-side
 * counterpart of a stored {@link Attribute}, used to target a cohort (e.g. a policy binding whose subjects are
 * "users where {@code department = engineering}"). Only equality is expressed today; richer operators are a
 * later extension.
 */
public record AttributePredicate(String key, String value) {

    /** Whether the given attributes satisfy this predicate (an exact key/value match among them). */
    public boolean matches(Iterable<Attribute> attributes) {
        for (Attribute attribute : attributes) {
            if (attribute.key().equals(key) && attribute.value().equals(value)) {
                return true;
            }
        }
        return false;
    }
}
