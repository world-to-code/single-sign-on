package com.example.sso.metadata.internal.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProfileRepository extends JpaRepository<ProfileEntity, UUID> {

    List<ProfileEntity> findByOrgIdOrderByName(UUID orgId);

    Optional<ProfileEntity> findByOrgIdAndName(UUID orgId, String name);

    Optional<ProfileEntity> findByIdAndOrgId(UUID id, UUID orgId);
}
