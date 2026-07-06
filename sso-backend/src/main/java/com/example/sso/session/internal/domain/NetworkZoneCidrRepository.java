package com.example.sso.session.internal.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Explicit access to a network zone's CIDR rows — the service issues the reads/inserts/deletes directly. */
public interface NetworkZoneCidrRepository extends JpaRepository<NetworkZoneCidr, NetworkZoneCidrId> {

    @Query("select c from NetworkZoneCidr c where c.id.zoneId = :zoneId")
    List<NetworkZoneCidr> findByZoneId(@Param("zoneId") UUID zoneId);

    @Modifying
    @Query("delete from NetworkZoneCidr c where c.id.zoneId = :zoneId")
    void deleteByZoneId(@Param("zoneId") UUID zoneId);
}
