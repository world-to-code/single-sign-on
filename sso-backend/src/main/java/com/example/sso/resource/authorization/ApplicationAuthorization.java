package com.example.sso.resource.authorization;

import java.util.Set;
import java.util.UUID;

/**
 * Scope decisions about applications: an app is in scope when it is a member of one of the actor's
 * managed resources (or the actor is unscoped). {@code appId} is the application's own id — an OIDC
 * {@code registered_client.id} or a SAML relying-party id — matching {@code resource_member.member_id}.
 * ABAC scope only; {@code canView} equals {@code canManage} until Phase 2.
 */
public interface ApplicationAuthorization {

    boolean canView(UUID actorUserId, String appId);

    boolean canManage(UUID actorUserId, String appId);

    /** Application ids inside the actor's managed resources. */
    Set<String> scopedAppIds(UUID actorUserId);
}
