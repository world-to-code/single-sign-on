package com.example.sso.metadata.internal.domain;

import com.example.sso.metadata.EntityKind;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** RLS-confined access to {@code entity_attribute} (a tenant sees its own + global rows). */
public interface EntityAttributeRepository extends JpaRepository<EntityAttribute, UUID> {

    List<EntityAttribute> findByEntityKindAndEntityIdOrderByAttrKey(EntityKind entityKind, String entityId);

    /**
     * The same rows for ONE tier. Named org-first so it matches {@code idx_entity_attribute_entity
     * (org_id, entity_kind, entity_id)} — the kind/id-only finder above cannot use it, because a btree needs
     * its leading column, and this is the ABAC hot table.
     */
    List<EntityAttribute> findByOrgIdAndEntityKindAndEntityIdOrderByAttrKey(UUID orgId, EntityKind entityKind,
            String entityId);

    List<EntityAttribute> findByOrgIdIsNullAndEntityKindAndEntityIdOrderByAttrKey(EntityKind entityKind,
            String entityId);

    /** All rows for several entities of one kind in a single query — the fan-in for attribute inheritance. */
    List<EntityAttribute> findByEntityKindAndEntityIdIn(EntityKind entityKind, Collection<String> entityIds);

    /** The acting tier's rows for one key — several now that a key may carry multiple values. */
    List<EntityAttribute> findByEntityKindAndEntityIdAndAttrKeyAndOrgId(
            EntityKind entityKind, String entityId, String attrKey, UUID orgId);

    List<EntityAttribute> findByEntityKindAndEntityIdAndAttrKeyAndOrgIdIsNull(
            EntityKind entityKind, String entityId, String attrKey);

    /** Whether the acting tier already holds this exact (key, value) — the idempotency guard for {@code add}. */
    boolean existsByEntityKindAndEntityIdAndAttrKeyAndAttrValueAndOrgId(
            EntityKind entityKind, String entityId, String attrKey, String attrValue, UUID orgId);

    boolean existsByEntityKindAndEntityIdAndAttrKeyAndAttrValueAndOrgIdIsNull(
            EntityKind entityKind, String entityId, String attrKey, String attrValue);

    /** Entity ids (as text) of the given kind carrying {@code key = value}. Distinct so a multi-value entity that
     *  happens to match is returned once (the cohort is a set of ids, not of rows). */
    @Query("select distinct a.entityId from EntityAttribute a "
            + "where a.entityKind = :kind and a.attrKey = :key and a.attrValue = :value")
    List<String> findEntityIds(@Param("kind") EntityKind kind, @Param("key") String key, @Param("value") String value);

    /** Entity ids carrying {@code key = value} OWNED by one org (excludes global rows) — a tier-scoped cohort. */
    @Query("select distinct a.entityId from EntityAttribute a where a.entityKind = :kind and a.attrKey = :key "
            + "and a.attrValue = :value and a.orgId = :org")
    List<String> findEntityIdsInOrg(@Param("kind") EntityKind kind, @Param("key") String key,
            @Param("value") String value, @Param("org") UUID org);

    /** Entity ids carrying {@code key = value} among the GLOBAL rows (org_id null) — the platform tier's cohort. */
    @Query("select distinct a.entityId from EntityAttribute a where a.entityKind = :kind and a.attrKey = :key "
            + "and a.attrValue = :value and a.orgId is null")
    List<String> findEntityIdsGlobal(@Param("kind") EntityKind kind, @Param("key") String key,
            @Param("value") String value);

    /** Entity ids carrying the key (any value) owned by one org — the tier-scoped EXISTS cohort (no globals). */
    @Query("select distinct a.entityId from EntityAttribute a "
            + "where a.entityKind = :kind and a.attrKey = :key and a.orgId = :org")
    List<String> findEntityIdsWithKeyInOrg(@Param("kind") EntityKind kind, @Param("key") String key,
            @Param("org") UUID org);

    /** Entity ids carrying the key (any value) among the GLOBAL rows — the platform tier's EXISTS cohort. */
    @Query("select distinct a.entityId from EntityAttribute a "
            + "where a.entityKind = :kind and a.attrKey = :key and a.orgId is null")
    List<String> findEntityIdsWithKeyGlobal(@Param("kind") EntityKind kind, @Param("key") String key);

    /** Entity ids carrying {@code key} with a value in {@code values}, owned by one org — the IN cohort, one query. */
    @Query("select distinct a.entityId from EntityAttribute a where a.entityKind = :kind and a.attrKey = :key "
            + "and a.attrValue in :values and a.orgId = :org")
    List<String> findEntityIdsWithValueInOrg(@Param("kind") EntityKind kind, @Param("key") String key,
            @Param("values") Collection<String> values, @Param("org") UUID org);

    /** Entity ids carrying {@code key} with a value in {@code values} among the GLOBAL rows — platform IN cohort. */
    @Query("select distinct a.entityId from EntityAttribute a where a.entityKind = :kind and a.attrKey = :key "
            + "and a.attrValue in :values and a.orgId is null")
    List<String> findEntityIdsWithValueInGlobal(@Param("kind") EntityKind kind, @Param("key") String key,
            @Param("values") Collection<String> values);

    // The CONTAINS cohort uses a native ILIKE (case-insensitive substring), accelerated by the pg_trgm GIN index
    // on attr_value (V103); {@code pattern} is the caller-escaped {@code %substring%}. entity_kind is the enum
    // NAME (varchar column), so the caller passes it as a String to keep native enum binding unambiguous.
    /** Entity ids of the kind whose value CONTAINS the pattern, owned by one org — the tier-scoped CONTAINS cohort. */
    @Query(value = "select distinct entity_id from entity_attribute where entity_kind = :kind and attr_key = :key "
            + "and attr_value ilike :pattern and org_id = :org", nativeQuery = true)
    List<String> findEntityIdsWithValueLikeInOrg(@Param("kind") String kind, @Param("key") String key,
            @Param("pattern") String pattern, @Param("org") UUID org);

    /** Entity ids of the kind whose value CONTAINS the pattern among the GLOBAL rows — the platform CONTAINS cohort. */
    @Query(value = "select distinct entity_id from entity_attribute where entity_kind = :kind and attr_key = :key "
            + "and attr_value ilike :pattern and org_id is null", nativeQuery = true)
    List<String> findEntityIdsWithValueLikeGlobal(@Param("kind") String kind, @Param("key") String key,
            @Param("pattern") String pattern);
}
