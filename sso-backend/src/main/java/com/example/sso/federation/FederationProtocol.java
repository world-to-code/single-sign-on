package com.example.sso.federation;

/**
 * The wire protocol an upstream identity provider speaks. Finite and code-bound — each value needs its own
 * login implementation, so this is an enum rather than data (unlike the vendor {@code presetId}, which is
 * display metadata a deployment can extend through configuration).
 */
public enum FederationProtocol {

    /** OpenID Connect: discovery + authorization code with PKCE, identity carried in a verified ID token. */
    OIDC,

    /** SAML 2.0: this product acts as the SP, identity carried in a signed assertion posted back to the ACS. */
    SAML
}
