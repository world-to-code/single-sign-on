package com.example.sso.user.group;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

/**
 * Admin view of an organizational group. memberUserIds = ids of the users that belong to it;
 * roles = the roles delegated to the group (inherited by every member).
 *
 * <p>{@code roles} carries id AND name because the delegation is written BY ID — a console that round-tripped
 * names would be re-introducing the resolution step that let a caller be authorized against one role and bind
 * another of the same name. It is the ONLY role field on the wire: a second, name-only one would be a
 * duplicate representation, and a derived accessor is not serialised anyway.
 */
public record GroupView(String id, String name, String description, String externalId,
                        List<String> memberUserIds, int memberCount, boolean system,
                        List<GroupRole> roles) {

    /**
     * Just the names, for an audit line — NOT part of the wire contract.
     *
     * <p>Jackson serialises a record's COMPONENTS; a derived accessor like this one is silently omitted unless
     * it is annotated. That is deliberate here: the console reads {@code roles} and derives names itself, so
     * shipping both would be two representations of one thing, and the response already carries the names
     * inside {@code roles}.
     */
    @JsonIgnore
    public List<String> roleNames() {
        return roles.stream().map(GroupRole::name).toList();
    }
}
