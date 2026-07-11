package com.example.sso.saml.internal.logout.application;

/**
 * Sends SAML {@code LogoutRequest}s to the SPs a terminated OP session did SSO to (looked up by {@code sid}).
 * Invoked from the session-termination listener; the event-driven path is browser-less, so it reaches only
 * back-channel (SOAP-binding) SPs — front-channel SPs are logged out via the explicit-logout redirect chain.
 */
interface SamlLogoutPropagation {

    void propagate(String sid, String username);
}
