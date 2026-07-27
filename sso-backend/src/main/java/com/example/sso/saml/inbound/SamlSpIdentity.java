package com.example.sso.saml.inbound;

import jakarta.servlet.http.HttpServletRequest;

/**
 * How this product identifies itself to an upstream IdP, per federated connection.
 *
 * <p>Both values are derived from the REQUEST HOST, so a tenant on its own subdomain federates under its own SP
 * identity — the same host-derived rule the IdP-role entityID follows. They are also per {@code alias}: one
 * tenant may federate to several upstreams, and each deserves a distinct SP identity so an assertion minted for
 * one connection is not addressed to another.
 */
public interface SamlSpIdentity {

    /** The SP entityID an AuthnRequest is issued under, and the audience an assertion must be addressed to. */
    String entityId(HttpServletRequest request, String alias);

    /** The assertion consumer service URL the upstream posts back to. */
    String acsUrl(HttpServletRequest request, String alias);
}
