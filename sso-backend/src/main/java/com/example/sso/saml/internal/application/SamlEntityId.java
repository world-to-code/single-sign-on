package com.example.sso.saml.internal.application;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Derives the IdP SAML entityID from the request host, mirroring the per-tenant OIDC issuer: a request to
 * {@code acme.idp.example.com} yields {@code https://acme.idp.example.com/saml2/idp}, so a tenant's
 * assertions/metadata carry an entityID that matches its own host and its own signing credential. The bare
 * platform host derives back to the configured {@code sso.saml.entity-id}. A tenant's SP is configured with
 * this host-scoped entityID, so the per-tenant signature verifies against the per-tenant metadata. The
 * scheme's default port (443/80) is omitted — like the OIDC issuer — so prod entityIDs carry no port while
 * dev keeps its explicit {@code :9000}. Port canonicalization is delegated to Spring's URI builder rather
 * than re-implemented here.
 */
@Component
public class SamlEntityId {

    private static final String IDP_PATH = "/saml2/idp";

    /** The IdP entityID for the host this request arrived on. */
    public String resolve(HttpServletRequest request) {
        return ServletUriComponentsBuilder.fromRequest(request)
                .replacePath(IDP_PATH)
                .replaceQuery(null)
                .toUriString();
    }
}
