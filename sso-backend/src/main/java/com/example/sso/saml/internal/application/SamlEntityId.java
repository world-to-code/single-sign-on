package com.example.sso.saml.internal.application;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Derives the IdP SAML entityID from the request host, mirroring the per-tenant OIDC issuer: a request to
 * {@code acme.idp.example.com} yields {@code http://acme.idp.example.com:9000/saml2/idp}, so a tenant's
 * assertions/metadata carry an entityID that matches its own host and its own signing credential. The bare
 * platform host derives back to the configured {@code sso.saml.entity-id}. A tenant's SP is configured with
 * this host-scoped entityID, so the per-tenant signature verifies against the per-tenant metadata.
 */
@Component
public class SamlEntityId {

    private static final String IDP_PATH = "/saml2/idp";

    /** The IdP entityID for the host this request arrived on. */
    public String resolve(HttpServletRequest request) {
        return request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort() + IDP_PATH;
    }
}
