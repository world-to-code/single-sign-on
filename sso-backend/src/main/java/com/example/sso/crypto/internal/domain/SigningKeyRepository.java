package com.example.sso.crypto.internal.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SigningKeyRepository extends JpaRepository<SigningKey, UUID> {

    /** The newest active key in the GLOBAL tier (org_id IS NULL) — the platform key and tenant fallback. */
    Optional<SigningKey> findFirstByActiveTrueAndOrgIdIsNullOrderByCreatedAtDesc();

    List<SigningKey> findAllByOrderByActiveDescCreatedAtDesc();
}
