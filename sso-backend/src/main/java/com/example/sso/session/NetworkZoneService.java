package com.example.sso.session;

import java.util.List;
import java.util.UUID;

/**
 * Manages the reusable {@link NetworkZoneView} catalog and resolves a zone's CIDRs for enforcement.
 * Session policies reference zones by id; {@link com.example.sso.security} evaluates a request's IP against
 * the resolved CIDRs (served from an in-memory cache, so no DB hit on the request path).
 */
public interface NetworkZoneService {

    List<NetworkZoneView> list();

    NetworkZoneView create(NetworkZoneSpec spec);

    NetworkZoneView update(UUID id, NetworkZoneSpec spec);

    void delete(UUID id);

    /** The zone's CIDR ranges (empty if the zone id is unknown). Served from the cache. */
    List<String> cidrsForZone(UUID zoneId);

    /** Whether a zone with this id exists — for validating a policy's zone reference. */
    boolean exists(UUID zoneId);
}
