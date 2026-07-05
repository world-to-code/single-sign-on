package com.example.sso.saml.internal.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SamlRelyingPartyRepository extends JpaRepository<SamlRelyingParty, UUID> {

    Optional<SamlRelyingParty> findByEntityId(String entityId);

    boolean existsByEntityId(String entityId);

    /** GLOBAL relying parties (no owning tenant) — the platform-tier admin list. */
    List<SamlRelyingParty> findAllByOrgIdIsNull();

    /** One tenant's relying parties — the drilled-in / tenant-admin list. */
    List<SamlRelyingParty> findAllByOrgId(UUID orgId);
}
