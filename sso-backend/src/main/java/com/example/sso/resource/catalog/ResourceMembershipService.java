package com.example.sso.resource.catalog;

import java.util.Optional;
import java.util.UUID;

/**
 * Programmatic USER-membership on a resource, for callers that have ALREADY authorized the operation (e.g. an
 * auto-mapping rule, whose author authority is checked at rule-create and re-validated at materialize). It keeps
 * the INTEGRITY invariants the admin path enforces — the resource's type must allow USER members, the user must
 * exist, and the user must live in the SAME org as the resource — but DROPS the current-actor subtree/tier
 * authorization (there is no acting admin on this path). Both writes are idempotent.
 */
public interface ResourceMembershipService {

    /** Add {@code userId} as a member of {@code resourceId} (idempotent), keeping the same-org / type invariants. */
    void addUser(UUID resourceId, UUID userId);

    /** Remove {@code userId} from {@code resourceId} (idempotent; a no-op when not a member). */
    void removeUser(UUID resourceId, UUID userId);

    /** The resource's display name if it resolves in the acting tier — existence + label for a rule's target. */
    Optional<String> nameOf(UUID resourceId);

    /** The resource's owning org (empty for a global resource), for a target's tier validation. */
    Optional<UUID> orgIdOf(UUID resourceId);
}
