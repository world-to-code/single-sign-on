package com.example.sso.session.internal.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NetworkZoneRepository extends JpaRepository<NetworkZone, UUID> {

    /** Name lookup within the GLOBAL tier (org_id IS NULL) — platform-wide zones. */
    Optional<NetworkZone> findByNameAndOrgIdIsNull(String name);

    /** Name lookup within one tenant's tier — used to reject a duplicate name inside the same org. */
    Optional<NetworkZone> findByNameAndOrgId(String name, UUID orgId);

    /** All zones with their CIDR sets fetch-joined, so the cache can hold them detached (a Set → no bag). */
    @Query("select distinct z from NetworkZone z left join fetch z.cidrs")
    List<NetworkZone> findAllWithCidrs();
}
