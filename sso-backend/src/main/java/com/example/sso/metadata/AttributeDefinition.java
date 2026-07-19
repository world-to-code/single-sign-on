package com.example.sso.metadata;

import java.util.List;
import java.util.UUID;

/**
 * One declared attribute in an organization's profile schema: what the key is called, what it holds, and who
 * owns its value. Definitions are a CATALOG, not a constraint — {@code entity_attribute} keeps accepting keys
 * that no definition describes, because live mapping rules and policy bindings reference keys as bare strings
 * and deleting a definition must not orphan them.
 *
 * <p>{@code enumValues} is populated only for {@link AttributeDataType#ENUM}.
 */
public record AttributeDefinition(UUID id, EntityKind entityKind, String key, String displayName,
                                  String description, AttributeDataType dataType, List<String> enumValues,
                                  boolean multiValued, boolean required, AttributeSource source, int sortOrder) {

    /** Whether an administrator may edit values of this attribute, as opposed to a directory owning them. */
    public boolean locallyEditable() {
        return source == AttributeSource.LOCAL;
    }
}
