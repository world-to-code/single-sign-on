package com.example.sso.directory.internal.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DirectoryAttributeMappingRepository extends JpaRepository<DirectoryAttributeMapping, UUID> {

    List<DirectoryAttributeMapping> findByConnectorIdOrderBySourceAttribute(UUID connectorId);

    void deleteByConnectorIdAndSourceAttribute(UUID connectorId, String sourceAttribute);
}
