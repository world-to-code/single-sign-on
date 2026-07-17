package com.example.sso.saml.logout;

import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;

/**
 * Stages a front-channel SAML Single Logout redirect chain for an explicit browser logout. Front-channel
 * (Redirect/POST) SPs can only be logged out while a browser is present, so — unlike the back-channel SOAP
 * path driven by the session-termination listener — this is initiated from the interactive logout.
 */
public interface SamlFrontChannelLogout {

    /**
     * If the terminating OP session ({@code sid}) had front-channel SAML participants, persists a redirect
     * chain and returns the URL the browser should navigate to to run it; otherwise empty. MUST be called
     * BEFORE the session is invalidated — it reads the participant index the termination listener clears.
     * Sets a browser-bound cookie on {@code response} so only this browser can drive the chain (the chain id
     * is disclosed to every participant SP as RelayState and therefore cannot be the sole capability).
     */
    Optional<String> startChain(String sid, HttpServletResponse response);

    /**
     * Like {@link #startChain} but for an SP-INITIATED {@code LogoutRequest}: stages the chain of the
     * session's front-channel SAML SPs EXCLUDING the initiator ({@code initiatorEntityId}) — which initiated
     * the logout and is instead answered with a {@code LogoutResponse} once the chain drains. {@code requestId}
     * (the inbound request's ID, for {@code InResponseTo}) and {@code relayState} are carried for that final
     * response. Empty when the session had no OTHER front-channel SP (the caller then answers the initiator
     * immediately). MUST be called BEFORE the session is invalidated (see {@link #startChain}).
     */
    Optional<String> startInboundChain(String sid, String initiatorEntityId, String requestId, String relayState,
                                       HttpServletResponse response);
}
