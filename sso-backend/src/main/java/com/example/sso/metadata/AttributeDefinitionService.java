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

    /** The acting tier's definitions for {@code kind}, in display order. Non-USER kinds only — a person's
     *  attributes live in a profile, see {@link #definitionsIn}. */
    List<AttributeDefinition> definitionsFor(EntityKind kind);

    /** One profile's USER attribute definitions, in display order. */
    List<AttributeDefinition> definitionsIn(UUID profileId);

    /** One profile's definition of a key, if it declares one. */
    Optional<AttributeDefinition> definitionIn(UUID profileId, String key);

    /** The acting tier's definition of one key, if it declares one. */
    Optional<AttributeDefinition> definitionOf(EntityKind kind, String key);

    /** Creates or replaces the definition of {@code spec.key()} within {@code profileId} (USER attributes). */
    AttributeDefinition save(UUID profileId, AttributeDefinitionSpec spec);

    /** Creates or replaces a non-USER definition — GROUP/APPLICATION/RESOURCE tags live outside profiles. */
    AttributeDefinition save(AttributeDefinitionSpec spec);

    void delete(UUID id);
}
