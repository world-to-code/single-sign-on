package com.example.sso.metadata;

import java.util.List;
import java.util.Set;

/**
 * Reads and writes an entity's metadata attributes. Every operation is org-scoped: writes stamp the acting
 * tenant tier and RLS confines reads, so a tenant only ever sees/edits its own (and the global) attributes.
 * The entity is addressed by its {@link EntityKind} and its id as a string (a uuid for user/group/resource,
 * the client_id / SAML entityId for an application) — the caller (which owns the entity) validates existence.
 */
public interface AttributeService {

    /** The attributes on the entity, in key order. */
    List<Attribute> attributesOf(EntityKind kind, String entityId);

    /** Sets (upserts) a single attribute value on the entity in the acting tier. */
    void set(EntityKind kind, String entityId, String key, String value);

    /** Removes an attribute from the entity in the acting tier; a no-op if absent. */
    void remove(EntityKind kind, String entityId, String key);

    /** The ids of the entities of this kind that carry {@code key = value} — the Phase-2 predicate lookup. */
    Set<String> entityIdsWith(EntityKind kind, String key, String value);
}
