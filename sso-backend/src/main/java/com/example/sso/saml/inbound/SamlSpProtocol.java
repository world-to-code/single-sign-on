package com.example.sso.saml.inbound;

/**
 * The SAML wire protocol of the SP role: build the request that starts a login, and verify what comes back.
 * Stateless — correlation, replay records and identity resolution belong to the caller.
 */
public interface SamlSpProtocol {

    /** A signed AuthnRequest encoded for HTTP-Redirect, carrying {@code relayState} back to the ACS. */
    SamlAuthnRequest buildAuthnRequest(UpstreamIdp upstream, String spEntityId, String acsUrl, String relayState);

    /**
     * Verifies a base64 SAML Response from the HTTP-POST binding against {@code expectations}, or throws. The
     * returned data is read exclusively from the assertion whose signature was verified.
     */
    VerifiedAssertion verify(String base64Response, AssertionExpectations expectations);
}
