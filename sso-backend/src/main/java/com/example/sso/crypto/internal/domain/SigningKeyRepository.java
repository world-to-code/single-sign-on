package com.example.sso.crypto.internal.domain;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SigningKeyRepository extends JpaRepository<SigningKey, UUID> {

    /** The newest active key in the GLOBAL tier (org_id IS NULL) — the platform key and tenant fallback. */
    Optional<SigningKey> findFirstByActiveTrueAndOrgIdIsNullOrderByCreatedAtDesc();

    /** The newest active key owned by {@code orgId} — a tenant's own signing key, if it has one. */
    Optional<SigningKey> findFirstByActiveTrueAndOrgIdOrderByCreatedAtDesc(UUID orgId);

    /** The GLOBAL tier's keys, the ACTIVE one first then rotated-away by recency — the JWKS source order. */
    List<SigningKey> findByOrgIdIsNullOrderByActiveDescCreatedAtDesc(Limit limit);

    /** {@code orgId}'s own keys, the ACTIVE one first then rotated-away by recency — the JWKS source order. */
    List<SigningKey> findByOrgIdOrderByActiveDescCreatedAtDesc(UUID orgId, Limit limit);

    List<SigningKey> findAllByOrderByActiveDescCreatedAtDesc();
}
