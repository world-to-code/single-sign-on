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
 * @param actor     who performed the move, captured on the request thread. Distinct from {@code subject} on
 *                  purpose: recording the person whose attributes were deleted as the actor would read as
 *                  "bob deleted bob's attributes" and leave the administrator who did it out of the trail.
 * @param subjectId whose profile changed, as the id the audit console resolves a USER subject by. The
 *                  username reads better but is not that key: a scoped admin's view parses the subject as a
 *                  UUID against the users they manage, so a name there drops the row from their console
 *                  entirely — the most destructive operation on the page, invisible to exactly the delegate
 *                  most worth watching.
 * @param subject   the same person's username, kept for the detail text a reader actually recognises
 */
record ProfileSwitched(String actor, UUID subjectId, String subject, UUID orgId, UUID profileId,
                       List<String> removedKeys) {
}
