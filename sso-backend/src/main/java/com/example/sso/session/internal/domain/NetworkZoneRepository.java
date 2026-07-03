package com.example.sso.session.internal.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NetworkZoneRepository extends JpaRepository<NetworkZone, UUID> {

    Optional<NetworkZone> findByName(String name);

    /** All zones with their CIDR sets fetch-joined, so the cache can hold them detached (a Set → no bag). */
    @Query("select distinct z from NetworkZone z left join fetch z.cidrs")
    List<NetworkZone> findAllWithCidrs();
}
