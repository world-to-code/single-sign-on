package com.example.sso.metadata;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Reads and writes an entity's metadata attributes. Every operation is org-scoped: writes stamp the acting
 * tenant tier and RLS confines reads, so a tenant only ever sees/edits its own (and the global) attributes.
 * The entity is addressed by its {@link EntityKind} and its id as a string (a uuid for user/group/resource,
 * the client_id / SAML entityId for an application) — the caller (which owns the entity) validates existence.
 */
public interface AttributeService {

    /** The attributes on the entity, in key order. On read a tenant's own attribute shadows a global one. */
    List<Attribute> attributesOf(EntityKind kind, String entityId);

    /**
     * The UNION of the (effective) attributes across several entities of one kind — own-shadows-global applied
     * per entity, then merged into one set (entity identity discarded; order not significant). The fan-in for
     * attribute inheritance: a caller that has resolved a user's group ids folds in
     * {@code unionAttributesOf(GROUP, groupIds)} so a predicate matches on a group's tag.
     */
    List<Attribute> unionAttributesOf(EntityKind kind, Collection<String> entityIds);

    /**
     * The entity's attributes OWNED by the acting tier only (a tenant's own rows, or the global rows at the
     * platform tier) — never the global rows a tenant merely inherits. The read-side counterpart of
     * {@link #entityIdsWithInTier}: a tier-scoped decision (e.g. auto-mapping) evaluates against this so a
     * platform-set global attribute never silently drives a tenant's rule.
     */
    List<Attribute> attributesOfInTier(EntityKind kind, String entityId);

    /** Sets (upserts) a single attribute value on the entity in the acting tier. */
    void set(EntityKind kind, String entityId, String key, String value);

    /** Removes an attribute from the entity in the acting tier; a no-op if absent. */
    void remove(EntityKind kind, String entityId, String key);

    /**
     * The ids of the entities of this kind that carry {@code key = value}. CAVEAT: this is a flat match over
     * the RLS-visible rows and does NOT apply the own-shadows-global precedence that {@link #attributesOf} does,
     * so a global value can still match after a tenant overrode it. A caller that drives access decisions off
     * this must first resolve the effective per-tenant value (or decide whether global values participate).
     */
    Set<String> entityIdsWith(EntityKind kind, String key, String value);

    /**
     * The ids of the entities of this kind carrying {@code key = value} in the ACTING TIER only — a tenant's own
     * rows (or, at the platform tier, the global rows), never the global rows a tenant merely inherits. This is
     * the org-isolated, precedence-correct cohort a tier-scoped decision (e.g. auto-mapping) needs, unlike the
     * flat {@link #entityIdsWith}.
     */
    Set<String> entityIdsWithInTier(EntityKind kind, String key, String value);

    /**
     * The ids of the entities of this kind carrying the key with ANY value, in the ACTING TIER only — the
     * value-less (EXISTS) counterpart of {@link #entityIdsWithInTier}, for a predicate that tests key presence
     * rather than a specific value.
     */
    Set<String> entityIdsWithKeyInTier(EntityKind kind, String key);

    /**
     * The ids of the entities of this kind carrying {@code key} with a value in {@code values}, in the ACTING
     * TIER only — the IN cohort (a union over the values) resolved in ONE query rather than per value.
     */
    Set<String> entityIdsWithAnyValueInTier(EntityKind kind, String key, Collection<String> values);
}
