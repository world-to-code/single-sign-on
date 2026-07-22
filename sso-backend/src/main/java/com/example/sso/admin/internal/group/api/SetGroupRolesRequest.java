package com.example.sso.admin.internal.group.api;

import java.util.Set;
import java.util.UUID;

/**
 * Replaces the roles delegated to a group.
 *
 * <p>IDS, not names. A role name resolves org-first with a global fallback, while the delegation is stored as
 * an id — so a by-name request let a tenant admin mint a benign local role of the same name, clear the
 * assignment ceiling on that one, and have the privileged GLOBAL role bound to the group instead. Two partial
 * unique indexes permit both rows to exist, so the collision was the caller's to create. With ids there is no
 * name for two roles to share.
 */
public record SetGroupRolesRequest(Set<UUID> roleIds) {
}
