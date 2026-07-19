package com.example.sso.metadata;

import java.util.List;

/** Immutable command to create or replace an attribute definition within the acting tier. */
public record AttributeDefinitionSpec(EntityKind entityKind, String key, String displayName, String description,
                                      AttributeDataType dataType, List<String> enumValues, boolean multiValued,
                                      boolean required, AttributeSource source, int sortOrder) {
}
