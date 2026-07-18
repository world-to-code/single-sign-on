package com.example.sso.portal.application;

import java.util.List;
import java.util.Set;

/**
 * SPI implemented per protocol to publish the applications a user's sessions still hold — and to log ONE of
 * them out from the IdP without ending the session (portal goal ③). Mirrors {@link ApplicationSource}: the
 * portal aggregates every source without importing the protocol modules, so there is no module cycle.
 *
 * <p>Callers pass the CALLER'S OWN session {@code sid}s; a source never resolves another user's sessions, and
 * the portal re-verifies ownership before dispatching {@link #logout} — so a source may trust its inputs.
 */
public interface AppSessionSource {

    /** The application kind this source publishes ({@link AppType#OIDC} or {@link AppType#SAML}). */
    AppType type();

    /** The applications of this kind still held under any of the given session {@code sid}s. */
    List<AppSessionParticipation> participationsFor(Set<String> sids);

    /**
     * Logs {@code username} out of ONE application ({@code appId}) for ONE session ({@code sid}) — delivering
     * the protocol's single-participant logout and dropping just that app from the session's participant index
     * (only once delivery is settled — a transiently-failed app is kept so a later whole-session logout still
     * re-drives it), leaving the IdP session and every other app alive.
     *
     * <p>Precondition: the app is one-click capable ({@link AppSessionParticipation#oneClickLogoutSupported()}).
     * The aggregator enforces this before dispatch; an implementation MAY additionally refuse a non-capable app
     * rather than silently settle one that can never be reached (a SAML front-channel-only SP).
     */
    void logout(String sid, String appId, String username);
}
