package com.example.sso.user;

import java.util.List;

/**
 * Admin view of an organizational group. memberUserIds = ids of the users that belong to it;
 * roleNames = the roles delegated to the group (inherited by every member).
 */
public record GroupView(String id, String name, String description, String externalId,
                        List<String> memberUserIds, int memberCount, boolean system,
                        List<String> roleNames) {
}
