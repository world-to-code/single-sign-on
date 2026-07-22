package com.example.sso.user.group;

import java.util.List;

/**
 * Admin view of an organizational group. memberUserIds = ids of the users that belong to it;
 * roles = the roles delegated to the group (inherited by every member).
 *
 * <p>{@code roles} carries id AND name because the delegation is written BY ID — a console that round-tripped
 * names would be re-introducing the resolution step that let a caller be authorized against one role and bind
 * another of the same name. {@code roleNames} stays for readers that only display, and is derived from the
 * same list so the two cannot disagree.
 */
public record GroupView(String id, String name, String description, String externalId,
                        List<String> memberUserIds, int memberCount, boolean system,
                        List<GroupRole> roles) {

    /** Just the names, in the order {@code roles} is already sorted — for display and for audit trails. */
    public List<String> roleNames() {
        return roles.stream().map(GroupRole::name).toList();
    }
}
