package com.example.sso.session.internal.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NetworkZoneRepository extends JpaRepository<NetworkZone, UUID> {

    /** Name lookup within the GLOBAL tier (org_id IS NULL) — platform-wide zones. */
    Optional<NetworkZone> findByNameAndOrgIdIsNull(String name);

    /** Name lookup within one tenant's tier — used to reject a duplicate name inside the same org. */
    Optional<NetworkZone> findByNameAndOrgId(String name, UUID orgId);
}
