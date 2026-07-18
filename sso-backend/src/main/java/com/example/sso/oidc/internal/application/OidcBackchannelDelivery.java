package com.example.sso.oidc.internal.application;

import com.example.sso.oidc.BackChannelLogout;
import com.example.sso.organization.OrganizationService;
import com.example.sso.tenancy.OrgContext;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Delivers ONE OIDC back-channel {@code logout_token} to a single client, bound to the CLIENT'S tenant so the
 * org-scoped registry can see it and the shared JWKSource signs the token with that tenant's key — matching
 * the per-tenant issuer the client validates against. Shared by the whole-session fan-out
 * ({@link LogoutPropagationImpl}) and the self-service per-app logout ({@link OidcParticipantSessionsImpl}) so
 * both send the exact same tenant-correct, sid-scoped token; the classification outcome drives the callers'
 * clear-only-delivered / retry decisions.
 */
@Component
class OidcBackchannelDelivery {

    private static final Logger log = LoggerFactory.getLogger(OidcBackchannelDelivery.class);

    private final RegisteredClientRepository clients;
    private final LogoutTokenFactory tokens;
    private final OrgContext orgContext;
    private final OrganizationService organizations;
    private final JdbcTemplate jdbc;
    private final String baseIssuer;
    private final RestClient http;

    OidcBackchannelDelivery(RegisteredClientRepository clients, LogoutTokenFactory tokens, OrgContext orgContext,
            OrganizationService organizations, JdbcTemplate jdbc, @Value("${sso.issuer}") String baseIssuer,
            @Value("${sso.oidc.backchannel.http-timeout:PT5S}") Duration timeout) {
        this.clients = clients;
        this.tokens = tokens;
        this.orgContext = orgContext;
        this.organizations = organizations;
        this.jdbc = jdbc;
        this.baseIssuer = baseIssuer;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * Builds and delivers the {@code logout_token} to one client (by its globally-unique internal id) under its
     * own tenant scope, classifying the result: TERMINAL (client gone / not a back-channel client) is settled
     * without retry; TRANSIENT (endpoint unreachable or token not buildable right now) is kept for the durable
     * retry sweep; DELIVERED is settled. Resolving by the internal id (not the per-tenant {@code client_id})
     * keeps the org — and thus the signing key/issuer/endpoint — unambiguous even when two tenants share a
     * {@code client_id}.
     */
    BackchannelDeliveryOutcome deliver(String registeredClientId, String subject, String sid) {
        UUID clientOrg = clientOrg(registeredClientId);
        String issuer = issuerFor(clientOrg);
        return clientOrg == null
                ? sendLogout(registeredClientId, subject, sid, issuer)
                : orgContext.callInOrg(clientOrg, () -> sendLogout(registeredClientId, subject, sid, issuer));
    }

    /** True when the client is registered AND configured with a back-channel logout URI (one-click capable). */
    boolean supportsBackChannelLogout(RegisteredClient client) {
        Object uri = client.getClientSettings().getSetting(BackChannelLogout.CLIENT_SETTING_URI);
        return uri instanceof String logoutUri && !logoutUri.isBlank();
    }

    private BackchannelDeliveryOutcome sendLogout(String registeredClientId, String subject, String sid,
            String issuer) {
        RegisteredClient client = clients.findById(registeredClientId);
        if (client == null) {
            return BackchannelDeliveryOutcome.TERMINAL;
        }
        if (!supportsBackChannelLogout(client)) {
            return BackchannelDeliveryOutcome.TERMINAL; // client not configured for back-channel logout
        }
        Object uri = client.getClientSettings().getSetting(BackChannelLogout.CLIENT_SETTING_URI);
        boolean sessionRequired = Boolean.TRUE.equals(
                client.getClientSettings().getSetting(BackChannelLogout.CLIENT_SETTING_SESSION_REQUIRED));
        try {
            // aud = the client's own client_id (resolved from the unambiguous id) — the RP validates it.
            String token = tokens.create(client.getClientId(), subject, sessionRequired ? sid : null, issuer);
            return post((String) uri, token)
                    ? BackchannelDeliveryOutcome.DELIVERED
                    : BackchannelDeliveryOutcome.TRANSIENT;
        } catch (RuntimeException e) {
            log.warn("back-channel logout to {} failed to build/send: {}", client.getClientId(), e.getMessage());
            return BackchannelDeliveryOutcome.TRANSIENT;
        }
    }

    // The owning org of a client by its internal id (null = a global/platform client). Direct lookup by the
    // PRIMARY KEY — unambiguous even across tenants — and RLS-free (oauth2_registered_client is not RLS-scoped),
    // so it resolves regardless of the ambient (platform) propagation context.
    private UUID clientOrg(String registeredClientId) {
        return jdbc.query("select org_id from oauth2_registered_client where id = ?",
                rs -> rs.next() ? rs.getObject("org_id", UUID.class) : null, registeredClientId);
    }

    // The issuer a client's tokens were minted under: the platform issuer for a global client, or the
    // tenant's host-derived issuer ({slug}.{base-host}) for an org client — matching the per-tenant issuer.
    private String issuerFor(UUID orgId) {
        if (orgId == null) {
            return baseIssuer;
        }
        return organizations.findView(orgId).map(view -> tenantIssuer(view.slug())).orElse(baseIssuer);
    }

    private String tenantIssuer(String slug) {
        URI base = URI.create(baseIssuer);
        String port = base.getPort() > 0 ? ":" + base.getPort() : "";
        return base.getScheme() + "://" + slug + "." + base.getHost() + port + base.getRawPath();
    }

    private boolean post(String uri, String logoutToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("logout_token", logoutToken);
        try {
            http.post().uri(uri).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().toBodilessEntity();
            return true;
        } catch (RestClientException e) {
            log.warn("back-channel logout POST to {} failed: {}", uri, e.getMessage());
            return false;
        }
    }
}
