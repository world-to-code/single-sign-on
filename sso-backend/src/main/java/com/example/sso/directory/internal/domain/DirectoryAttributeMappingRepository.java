package com.example.sso.directory.internal.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DirectoryAttributeMappingRepository extends JpaRepository<DirectoryAttributeMapping, UUID> {

    List<DirectoryAttributeMapping> findByConnectorIdOrderBySourceAttribute(UUID connectorId);

    Optional<DirectoryAttributeMapping> findByConnectorIdAndSourceAttribute(UUID connectorId,
            String sourceAttribute);

    List<DirectoryAttributeMapping> findByOrgIdAndTargetKeyIn(UUID orgId, Collection<String> targetKeys);

    List<DirectoryAttributeMapping> findByOrgIdIsNullAndTargetKeyIn(Collection<String> targetKeys);
}
