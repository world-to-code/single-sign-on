package com.example.sso.portal.application;

/**
 * The kind of application a policy binding targets: an OIDC client, a SAML service provider, or an
 * IdP-served portal ({@code PORTAL} — the admin console or the end-user portal).
 */
public enum AppType {
    OIDC, SAML, PORTAL
}
