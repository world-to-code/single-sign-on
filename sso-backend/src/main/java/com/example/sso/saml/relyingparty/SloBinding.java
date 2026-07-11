package com.example.sso.saml.relyingparty;

/**
 * How the IdP delivers a SAML {@code LogoutRequest} to a relying party's Single Logout endpoint.
 * REDIRECT/POST are front-channel (browser-driven — explicit logout only); SOAP is back-channel
 * (server-to-server — also covers idle expiry, like OIDC back-channel logout).
 */
public enum SloBinding {
    REDIRECT,
    POST,
    SOAP
}
