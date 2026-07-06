package com.example.sso.resource.internal.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResourceTypeRepository extends JpaRepository<ResourceType, UUID> {

    Optional<ResourceType> findByName(String name);

    List<ResourceType> findAllByOrderByName();
}
