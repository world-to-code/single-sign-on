package com.example.sso.user;

import java.util.Set;
import java.util.UUID;

/** A group together with its legacy managers — the input to the one-time resource-role migration. */
public record GroupManagers(UUID groupId, String groupName, Set<UUID> managerIds) {
}
