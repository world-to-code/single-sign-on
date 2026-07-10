package com.example.sso.crypto.internal.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SigningKeyRetentionRepository extends JpaRepository<SigningKeyRetention, UUID> {

    /** The tenant's own retention row, if it has customized one. */
    Optional<SigningKeyRetention> findByOrgId(UUID orgId);

    /** The GLOBAL default row (org_id IS NULL) every tenant inherits until it saves its own. */
    Optional<SigningKeyRetention> findByOrgIdIsNull();
}
