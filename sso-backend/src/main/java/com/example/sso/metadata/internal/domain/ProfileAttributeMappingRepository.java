package com.example.sso.metadata.internal.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProfileAttributeMappingRepository extends JpaRepository<ProfileAttributeMapping, UUID> {

    List<ProfileAttributeMapping> findBySourceProfileIdOrderBySourceAttrKey(UUID sourceProfileId);

    Optional<ProfileAttributeMapping> findBySourceProfileIdAndSourceAttrKey(UUID sourceProfileId, String key);

    Optional<ProfileAttributeMapping> findByIdAndOrgId(UUID id, UUID orgId);

    List<ProfileAttributeMapping> findByTargetProfileIdAndTargetAttrKeyIn(UUID targetProfileId,
            Collection<String> targetAttrKeys);
}
