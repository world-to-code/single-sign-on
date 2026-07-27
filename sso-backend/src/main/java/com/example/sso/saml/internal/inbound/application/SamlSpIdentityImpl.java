package com.example.sso.saml.internal.inbound.application;

import com.example.sso.saml.inbound.SamlSpIdentity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Host-derived SP identity, mirroring {@code SamlEntityId} on the IdP side: the tenant a request arrived at
 * determines who we are to the upstream, so no configured base URL can drift from the origin the browser
 * actually used. Spring's builder omits a default port, keeping the entityID byte-stable across http/https
 * defaults — an entityID is compared as a string by the upstream, so a stray {@code :443} would break the
 * connection.
 */
@Component
class SamlSpIdentityImpl implements SamlSpIdentity {

    private static final String SP_PATH = "/saml2/sp/";

    /** The ACS lives under the federation API, not under /saml2, so it inherits that prefix's anonymous access
     *  and — more importantly — its rate limit: the ACS is an unauthenticated endpoint that can create an
     *  account, which is exactly what {@code AuthRateLimitFilter} covers there. */
    private static final String ACS_PATH = "/api/auth/federation/";

    @Override
    public String entityId(HttpServletRequest request, String alias) {
        return origin(request).replacePath(SP_PATH + alias).toUriString();
    }

    @Override
    public String acsUrl(HttpServletRequest request, String alias) {
        return origin(request).replacePath(ACS_PATH + alias + "/acs").toUriString();
    }

    private UriComponentsBuilder origin(HttpServletRequest request) {
        return ServletUriComponentsBuilder.fromRequest(request).replaceQuery(null);
    }
}
