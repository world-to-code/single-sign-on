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
}
