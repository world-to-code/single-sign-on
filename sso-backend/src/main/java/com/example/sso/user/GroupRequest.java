package com.example.sso.user;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Create/update request for an organizational group. externalId is the optional source id for a
 * future LDAP/SCIM sync; memberUserIds is the wholesale membership for the group.
 */
public record GroupRequest(@NotBlank String name, String description, String externalId,
                           List<String> memberUserIds) {

    /** The create/update command, resolving the member id strings to UUIDs. */
    public GroupSpec toSpec() {
        Set<UUID> members = memberUserIds == null ? Set.of()
                : memberUserIds.stream().map(UUID::fromString).collect(Collectors.toSet());
        return new GroupSpec(name, description, externalId, members);
    }
}
