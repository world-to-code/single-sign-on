package com.example.sso.saml.inbound;

/**
 * A built AuthnRequest: where to send the browser, and the request id the eventual assertion must echo in
 * {@code InResponseTo}. The caller stores that id so the ACS can prove the assertion answers a request WE made.
 */
public record SamlAuthnRequest(String redirectUrl, String requestId) {
}
