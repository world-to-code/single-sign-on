package com.example.sso.directory.internal.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Explicit-tier finders, never ambient RLS — a connector belongs to one tier and is not inherited. */
public interface DirectoryConnectorRepository extends JpaRepository<DirectoryConnector, UUID> {

    List<DirectoryConnector> findByOrgIdOrderByName(UUID orgId);

    List<DirectoryConnector> findByOrgIdIsNullOrderByName();

    Optional<DirectoryConnector> findByOrgIdAndName(UUID orgId, String name);

    Optional<DirectoryConnector> findByOrgIdIsNullAndName(String name);

    Optional<DirectoryConnector> findByIdAndOrgId(UUID id, UUID orgId);

    Optional<DirectoryConnector> findByIdAndOrgIdIsNull(UUID id);

    /** Every enabled connector across all tiers — the scheduled sweep's enumeration, run as platform. */
    List<DirectoryConnector> findByEnabledTrueOrderById();
}
