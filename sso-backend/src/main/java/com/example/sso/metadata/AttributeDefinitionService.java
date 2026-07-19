package com.example.sso.metadata;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The acting tenant's profile schema: which attributes exist for an entity kind, and who owns each one.
 *
 * <p>Scoped strictly per tier — a definition is a tenant's own schema and is not inherited from the platform
 * tier, so a tenant neither reads nor edits global definitions.
 */
public interface AttributeDefinitionService {

    /** The acting tier's definitions for {@code kind}, in display order. */
    List<AttributeDefinition> definitionsFor(EntityKind kind);

    /** The acting tier's definition of one key, if it declares one. */
    Optional<AttributeDefinition> definitionOf(EntityKind kind, String key);

    /** Creates or replaces the definition of {@code spec.key()} within the acting tier. */
    AttributeDefinition save(AttributeDefinitionSpec spec);

    void delete(UUID id);
}
