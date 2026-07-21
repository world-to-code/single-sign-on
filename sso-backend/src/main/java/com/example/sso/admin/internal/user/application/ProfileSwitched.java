package com.example.sso.admin.internal.user.application;

import java.util.List;
import java.util.UUID;

/**
 * A user was moved onto another profile, losing {@code removedKeys}.
 *
 * <p>Carries the keys, never the values: the point of the record is that an attribute deletion can retract an
 * ABAC-granted role, so someone needs to be able to attribute the change afterwards — the values themselves
 * are the user's data and belong nowhere near an audit row.
 *
 * @param actor   who performed the move, captured on the request thread. Distinct from {@code subject} on
 *                purpose: recording the person whose attributes were deleted as the actor would read as
 *                "bob deleted bob's attributes" and leave the administrator who did it out of the trail.
 * @param subject whose profile changed
 */
record ProfileSwitched(String actor, String subject, UUID orgId, UUID profileId, List<String> removedKeys) {
}
