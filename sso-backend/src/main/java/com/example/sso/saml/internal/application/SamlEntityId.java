package com.example.sso.saml.internal.application;

import com.example.sso.organization.OrganizationService;
import com.example.sso.organization.OrganizationView;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Derives the IdP SAML entityID, mirroring the per-tenant OIDC issuer: a request to
 * {@code acme.idp.example.com} yields {@code https://acme.idp.example.com/saml2/idp}, so a tenant's
 * assertions/metadata carry an entityID that matches its own host and its own signing credential. The bare
 * platform host derives back to the configured {@code sso.saml.entity-id}. A tenant's SP is configured with
 * this host-scoped entityID, so the per-tenant signature verifies against the per-tenant metadata. The
 * scheme's default port (443/80) is omitted — like the OIDC issuer — so prod entityIDs carry no port while
 * dev keeps its explicit {@code :9000}. Port canonicalization is delegated to Spring's URI builder rather
 * than re-implemented here.
 *
 * <p>{@link #forOrg} serves the browser-less paths (back-channel SLO), where there is no Host header to
 * derive from: the tenant's entityID is rebuilt from its slug against the configured platform entityID,
 * exactly as the OIDC back-channel logout rebuilds the per-tenant issuer.
 */
@Component
public class SamlEntityId {

    private static final String IDP_PATH = "/saml2/idp";

    private final OrganizationService organizations;
    private final String platformEntityId;

    public SamlEntityId(OrganizationService organizations,
            @Value("${sso.saml.entity-id}") String platformEntityId) {
        this.organizations = organizations;
        this.platformEntityId = platformEntityId;
    }

    /** The IdP entityID for the host this request arrived on. */
    public String resolve(HttpServletRequest request) {
        return ServletUriComponentsBuilder.fromRequest(request)
                .replacePath(IDP_PATH)
                .replaceQuery(null)
                .toUriString();
    }

    /**
     * The IdP entityID a tenant's messages are issued under ({@code {slug}.{platform-host}}), for paths with
     * no request to derive a host from. A global relying party (or an org that no longer resolves) keeps the
     * platform entityID.
     */
    public String forOrg(UUID orgId) {
        if (orgId == null) {
            return platformEntityId;
        }
        return organizations.findView(orgId)
                .map(OrganizationView::slug)
                .map(this::tenantEntityId)
                .orElse(platformEntityId);
    }

    private String tenantEntityId(String slug) {
        URI base = URI.create(platformEntityId);
        String port = base.getPort() > 0 ? ":" + base.getPort() : "";
        return base.getScheme() + "://" + slug + "." + base.getHost() + port + base.getRawPath();
    }
}
