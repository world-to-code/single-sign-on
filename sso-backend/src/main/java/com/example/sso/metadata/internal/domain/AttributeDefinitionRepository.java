package com.example.sso.metadata.internal.domain;

import com.example.sso.metadata.EntityKind;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Explicit-tier finders, never ambient RLS: a definition belongs to exactly one tier and is not inherited, so
 * every read names the tier it means. RLS is the backstop; these signatures are the first line.
 */
public interface AttributeDefinitionRepository extends JpaRepository<AttributeDefinitionEntity, UUID> {

    List<AttributeDefinitionEntity> findByOrgIdAndEntityKindOrderBySortOrderAscAttrKeyAsc(UUID orgId,
            EntityKind entityKind);

    List<AttributeDefinitionEntity> findByOrgIdIsNullAndEntityKindOrderBySortOrderAscAttrKeyAsc(
            EntityKind entityKind);

    Optional<AttributeDefinitionEntity> findByOrgIdAndEntityKindAndAttrKey(UUID orgId, EntityKind entityKind,
            String attrKey);

    Optional<AttributeDefinitionEntity> findByOrgIdIsNullAndEntityKindAndAttrKey(EntityKind entityKind,
            String attrKey);

    Optional<AttributeDefinitionEntity> findByIdAndOrgId(UUID id, UUID orgId);

    Optional<AttributeDefinitionEntity> findByIdAndOrgIdIsNull(UUID id);
}
