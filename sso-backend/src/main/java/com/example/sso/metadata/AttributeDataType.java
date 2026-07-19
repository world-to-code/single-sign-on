package com.example.sso.metadata;

/**
 * The kind of value an attribute holds. Declared for INPUT VALIDATION and RENDERING only — the value itself is
 * stored as text, because the ABAC layer compares attribute values as strings throughout (the condition tables,
 * the SQL cohort queries, the trigram index and the in-memory matchers all assume it). Typing the storage would
 * mean a per-type path through every one of those.
 */
public enum AttributeDataType {

    STRING,
    INTEGER,
    BOOLEAN,
    DATE,
    /** A closed list; the permitted values live on the definition. */
    ENUM;

    public boolean requiresEnumValues() {
        return this == ENUM;
    }
}
