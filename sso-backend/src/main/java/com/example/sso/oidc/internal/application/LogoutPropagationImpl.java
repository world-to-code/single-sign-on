package com.example.sso.oidc.internal.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.oidc.BackChannelLogout;
import com.example.sso.oidc.OidcBackchannelSessionIndex;
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
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Looks up the clients that hold a token for the terminated session ({@code sid}) and POSTs each a signed
 * {@code logout_token} at its registered back-channel URI. Per-client failures are isolated and audited so
 * one unreachable RP never blocks the others; the mapping is cleared afterwards (one send per termination).
 */
@Service
class LogoutPropagationImpl implements LogoutPropagation {

    private static final Logger log = LoggerFactory.getLogger(LogoutPropagationImpl.class);

    private final OidcBackchannelSessionIndex index;
    private final RegisteredClientRepository clients;
    private final LogoutTokenFactory tokens;
    private final AuditService audit;
    private final OrgContext orgContext;
    private final OrganizationService organizations;
    private final JdbcTemplate jdbc;
    private final String baseIssuer;
    private final RestClient http;

    LogoutPropagationImpl(OidcBackchannelSessionIndex index, RegisteredClientRepository clients,
            LogoutTokenFactory tokens, AuditService audit, OrgContext orgContext,
            OrganizationService organizations, JdbcTemplate jdbc,
            @Value("${sso.issuer}") String baseIssuer,
            @Value("${sso.oidc.backchannel.http-timeout:PT5S}") Duration timeout) {
        this.index = index;
        this.clients = clients;
        this.tokens = tokens;
        this.audit = audit;
        this.orgContext = orgContext;
        this.organizations = organizations;
        this.jdbc = jdbc;
        this.baseIssuer = baseIssuer;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public void propagate(String sid, String username) {
        OidcBackchannelSessionIndex.Participants participants = index.lookup(sid);
        String subject = participants.username() != null ? participants.username() : username;
        for (String clientId : participants.clientIds()) {
            UUID clientOrg = clientOrg(clientId);
            String issuer = issuerFor(clientOrg);
            // Run per-client bound to the CLIENT'S tenant, so (a) the org-scoped client registry can see it
            // and (b) the shared JWKSource signs the logout token with that tenant's key — matching the
            // per-tenant issuer stamped on the token, which the RP validates against its own JWKS.
            boolean delivered = clientOrg == null
                    ? sendLogout(clientId, subject, sid, issuer)
                    : orgContext.callInOrg(clientOrg, () -> sendLogout(clientId, subject, sid, issuer));
            audit.record(new AuditRecord(AuditType.OIDC_BACKCHANNEL_LOGOUT, subject, delivered,
                    "client=" + clientId, null));
        }
        index.clear(sid); // idempotency: one dispatch per termination
    }

    // Builds and delivers the logout token to one client, isolating a token-build or network failure so one
    // client never starves the others; returns whether it was delivered.
    private boolean sendLogout(String clientId, String subject, String sid, String issuer) {
        RegisteredClient client = clients.findByClientId(clientId);
        if (client == null) {
            return false;
        }
        Object uri = client.getClientSettings().getSetting(BackChannelLogout.CLIENT_SETTING_URI);
        if (!(uri instanceof String logoutUri) || logoutUri.isBlank()) {
            return false; // client not configured for back-channel logout
        }
        boolean sessionRequired = Boolean.TRUE.equals(
                client.getClientSettings().getSetting(BackChannelLogout.CLIENT_SETTING_SESSION_REQUIRED));
        try {
            return post(logoutUri, tokens.create(clientId, subject, sessionRequired ? sid : null, issuer));
        } catch (RuntimeException e) {
            log.warn("back-channel logout to {} failed to build/send: {}", clientId, e.getMessage());
            return false;
        }
    }

    // The owning org of a client (null = a global/platform client). Direct lookup — oauth2_registered_client
    // is not RLS-scoped, so this resolves regardless of the ambient (platform) propagation context.
    private UUID clientOrg(String clientId) {
        return jdbc.query("select org_id from oauth2_registered_client where client_id = ?",
                rs -> rs.next() ? rs.getObject("org_id", UUID.class) : null, clientId);
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
