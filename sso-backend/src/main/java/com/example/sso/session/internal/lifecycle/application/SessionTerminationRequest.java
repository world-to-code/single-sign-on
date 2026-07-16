package com.example.sso.session.internal.lifecycle.application;

import java.util.UUID;

/**
 * A re-drivable request to terminate a user's sessions after an access change — the durable unit persisted in
 * Redis so a termination lost to a crash or a long store outage can be re-driven by the scheduled sweep, not
 * only audited. It carries either a resolved {@code username} (the disable/lock/re-role path already has it) or
 * a {@code userId} to resolve at re-drive time (the org-membership-revoke path), scoped to {@code orgId}
 * (usernames are unique only within an org; a {@code null} orgId is a global/platform account). Terminating is
 * idempotent — re-driving drops only sessions that still exist — so re-driving the same request is safe.
 */
record SessionTerminationRequest(UUID orgId, String username, UUID userId) {

    /** The disable/lock/re-role path: the username is already known, so no resolution is needed at re-drive. */
    static SessionTerminationRequest forUser(String username, UUID orgId) {
        return new SessionTerminationRequest(orgId, username, null);
    }

    /** The org-membership-revoke path: only the user id is known; the username is resolved when it is driven. */
    static SessionTerminationRequest forMember(UUID userId, UUID orgId) {
        return new SessionTerminationRequest(orgId, null, userId);
    }

    /**
     * A stable, unique key identifying the termination TARGET, so repeated failures for the same user in the
     * same org coalesce onto one durable entry (idempotent) rather than piling up. Opaque to the sweep, which
     * reconstructs the actual fields from the stored meta.
     */
    String key() {
        String subject = username != null ? "u:" + username : "i:" + userId;
        return (orgId == null ? "-" : orgId.toString()) + '|' + subject;
    }

    /** The subject to record on an audit event for this termination — the username, or the id when unresolved. */
    String auditPrincipal() {
        return username != null ? username : "user:" + userId;
    }
}
