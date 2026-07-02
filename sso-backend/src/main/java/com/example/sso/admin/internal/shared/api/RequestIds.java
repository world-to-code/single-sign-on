package com.example.sso.admin.internal.shared.api;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Parses a request's list of id strings into a {@code Set<UUID>} (null → empty), for the admin controllers. */
public final class RequestIds {

    public static Set<UUID> toUuidSet(List<String> values) {
        return values == null ? Set.of()
                : values.stream().map(UUID::fromString).collect(Collectors.toSet());
    }

    private RequestIds() {
    }
}
