package com.example.sso.session.networkzone;

import com.example.sso.session.internal.networkzone.domain.NetworkZone;

import java.util.List;

/** Admin view of a named network zone. */
public record NetworkZoneView(String id, String name, String description, List<String> cidrs) {

    public static NetworkZoneView of(NetworkZone zone, List<String> cidrs) {
        return new NetworkZoneView(zone.getId().toString(), zone.getName(), zone.getDescription(), cidrs);
    }
}
