package com.example.sso.organization;

import java.util.Set;
import java.util.UUID;

/**
 * Delegated-admin scoping for organizations: which orgs a scoped {@code ROLE_ORG_ADMIN} may administer
 * (the orgs they belong to). The super-admin bypass is applied by the caller ({@code AdminAccessPolicy});
 * this port only expresses the membership-based org-admin scope. Mirrors the resource module's
 * {@code UserAuthorization}/{@code GroupAuthorization} ports.
 */
public interface OrganizationAuthorization {

    /** Whether the actor is an org-admin AND a member of {@code orgId} (i.e. may administer that org). */
    boolean canManage(UUID actorUserId, UUID orgId);

    /** The orgs a scoped org-admin actor administers (their memberships); empty for a non-org-admin. */
    Set<UUID> scopedOrgIds(UUID actorUserId);
}
