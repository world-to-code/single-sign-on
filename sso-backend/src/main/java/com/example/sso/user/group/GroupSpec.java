package com.example.sso.user.group;

import java.util.Set;
import java.util.UUID;

/**
 * Immutable parameter object shared by {@link UserGroupService#create(GroupSpec)} and
 * {@link UserGroupService#update(UUID, GroupSpec)}: a group's directory attributes and its member set.
 */
public record GroupSpec(String name, String description, String externalId, Set<UUID> memberIds) {
}
