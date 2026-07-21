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

    /** The acting tenant's session policy for {@code portalAppId} (its own binding, else the inherited global).
     *  This is the RESOLUTION view — what actually governs the portal. */
    Optional<UUID> sessionPolicyId(String portalAppId);

    /**
     * Only the acting tenant's OWN binding, empty when it is inheriting the global default.
     *
     * <p>Distinct from {@link #sessionPolicyId} on purpose. An administration screen must not present an
     * inherited global id as though the tenant had selected it: the id is not in the tenant's own-tier policy
     * list, so the dropdown cannot show it, and echoing it back on save is refused by the write guard — which
     * blocked every unrelated setting on the same form.
     */
    Optional<UUID> ownSessionPolicyId(String portalAppId);

    /** Selects (or, when {@code null}, clears) the acting tenant's session policy for {@code portalAppId}. The
     *  policy must be one of the acting tenant's own tier; only the platform context may edit the global default. */
    void setSessionPolicy(String portalAppId, UUID sessionPolicyId);
}
