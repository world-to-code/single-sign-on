package com.example.sso.federation;

import java.util.List;
import java.util.UUID;

/**
 * Administration of the identities bound to a local account. Without this an identity binding is unrevocable
 * except by direct SQL, which turns every fail-closed guard on the login path into a permanent account lockout:
 * a user whose upstream subject changed, or whose account was claimed once by the wrong subject, has no way
 * back. Unlinking is therefore a first-class administrative act — audited, step-up gated, and terminating the
 * sessions the identity authenticated.
 *
 * <p>Scoped to the acting tenant: an administrator sees and revokes only identities belonging to its own
 * organization.
 */
public interface FederatedIdentityAdminService {

    /** The upstream identities bound to {@code userId} within the acting tenant, oldest first. */
    List<FederatedIdentityView> forUser(UUID userId);

    /** Revokes one identity and terminates the sessions it authenticated. */
    void unlink(UUID identityId);
}
