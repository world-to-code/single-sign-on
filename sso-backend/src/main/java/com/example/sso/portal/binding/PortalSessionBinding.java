package com.example.sso.portal.binding;

import java.util.Optional;
import java.util.UUID;

/**
 * The session policy governing an IdP-served portal (the admin console or the end-user portal — see
 * {@link PortalApps}) for the acting tenant, backed by the {@code policy_binding} matrix (an all-subjects
 * {@code PORTAL}/{@code <appId>} binding). A tenant sees its own selection, else the GLOBAL default it
 * inherits; isolation and tier rules are enforced on write. Empty means "no portal-specific policy" — the
 * portal then falls back to each subject's own resolved policy.
 */
public interface PortalSessionBinding {

    /** The acting tenant's session policy for {@code portalAppId} (its own binding, else the inherited global). */
    Optional<UUID> sessionPolicyId(String portalAppId);

    /** Selects (or, when {@code null}, clears) the acting tenant's session policy for {@code portalAppId}. The
     *  policy must be one of the acting tenant's own tier; only the platform context may edit the global default. */
    void setSessionPolicy(String portalAppId, UUID sessionPolicyId);
}
