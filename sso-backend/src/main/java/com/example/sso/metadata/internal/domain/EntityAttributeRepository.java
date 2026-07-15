package com.example.sso.metadata.internal.domain;

import com.example.sso.metadata.EntityKind;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** RLS-confined access to {@code entity_attribute} (a tenant sees its own + global rows). */
public interface EntityAttributeRepository extends JpaRepository<EntityAttribute, UUID> {

    List<EntityAttribute> findByEntityKindAndEntityIdOrderByAttrKey(EntityKind entityKind, String entityId);

    Optional<EntityAttribute> findByEntityKindAndEntityIdAndAttrKeyAndOrgId(
            EntityKind entityKind, String entityId, String attrKey, UUID orgId);

    Optional<EntityAttribute> findByEntityKindAndEntityIdAndAttrKeyAndOrgIdIsNull(
            EntityKind entityKind, String entityId, String attrKey);

    /** Entity ids (as text) of the given kind carrying {@code key = value}. */
    @Query("select a.entityId from EntityAttribute a "
            + "where a.entityKind = :kind and a.attrKey = :key and a.attrValue = :value")
    List<String> findEntityIds(@Param("kind") EntityKind kind, @Param("key") String key, @Param("value") String value);

    /** Entity ids carrying {@code key = value} OWNED by one org (excludes global rows) — a tier-scoped cohort. */
    @Query("select a.entityId from EntityAttribute a where a.entityKind = :kind and a.attrKey = :key "
            + "and a.attrValue = :value and a.orgId = :org")
    List<String> findEntityIdsInOrg(@Param("kind") EntityKind kind, @Param("key") String key,
            @Param("value") String value, @Param("org") UUID org);

    /** Entity ids carrying {@code key = value} among the GLOBAL rows (org_id null) — the platform tier's cohort. */
    @Query("select a.entityId from EntityAttribute a where a.entityKind = :kind and a.attrKey = :key "
            + "and a.attrValue = :value and a.orgId is null")
    List<String> findEntityIdsGlobal(@Param("kind") EntityKind kind, @Param("key") String key,
            @Param("value") String value);
}
