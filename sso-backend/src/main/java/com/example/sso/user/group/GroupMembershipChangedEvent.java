package com.example.sso.user.group;

import java.util.Set;
import java.util.UUID;

/**
 * Published when a group's membership changes (users added or removed). Carries the affected user ids and the
 * org they belong to, so a consumer can re-evaluate exactly those users whose GROUP-inherited state shifted —
 * e.g. auto-mapping re-materializes rules for users who gained or lost a group's attributes. A group-level
 * concern, distinct from the session-terminating {@code UserAccessChangedEvent}.
 */
public record GroupMembershipChangedEvent(Set<UUID> userIds, UUID orgId) {
}
