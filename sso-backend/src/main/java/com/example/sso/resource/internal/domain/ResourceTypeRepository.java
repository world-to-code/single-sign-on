package com.example.sso.resource.internal.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResourceTypeRepository extends JpaRepository<ResourceType, UUID> {

    /** A tenant's own type of this name (null orgId = the global one) — the tier-first lookup half. */
    Optional<ResourceType> findByNameAndOrgId(String name, UUID orgId);

    /** The GLOBAL/shared type of this name — the fallback half of the tier-first-then-global resolution. */
    Optional<ResourceType> findByNameAndOrgIdIsNull(String name);

    List<ResourceType> findAllByOrderByName();
}
