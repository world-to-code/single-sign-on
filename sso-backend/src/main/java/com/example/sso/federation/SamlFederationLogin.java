package com.example.sso.federation;

import java.util.UUID;

/**
 * The SAML half of inbound federation. Separate from {@link FederationLoginService} because the two protocols
 * share nothing on the wire: OIDC redirects with state/nonce/PKCE and returns to a same-site GET, SAML posts a
 * signed assertion cross-site and correlates by RelayState. What they DO share — resolving the local account and
 * establishing the session — happens above both, on the {@link FederatedIdentity} they each produce.
 */
public interface SamlFederationLogin {

    /** The URL to redirect the browser to, having recorded what the ACS will need to correlate the answer. */
    String beginLogin(UUID orgId, String alias, String spEntityId, String acsUrl, String browserHandle);

    /**
     * Verifies a posted assertion and returns the identity it asserts, with the tenant it belongs to. The SP
     * identity is NOT a parameter: it is read from the correlation record, so the audience and recipient are
     * compared against what the AuthnRequest was actually issued under rather than against anything the
     * incoming request could influence.
     */
    SamlLoginResult completeLogin(String alias, String samlResponse, String relayState,
            BrowserBinding browserBinding);
}
