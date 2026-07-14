package com.example.sso.oidc.internal.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.logoutretry.LogoutRetryCoordinator;
import com.example.sso.oidc.BackChannelLogout;
import com.example.sso.oidc.OidcBackchannelSessionIndex;
import com.example.sso.organization.OrganizationService;
import com.example.sso.tenancy.OrgContext;
import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
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
    private final LogoutRetryCoordinator retryCoordinator;
    private final JdbcTemplate jdbc;
    private final String baseIssuer;
    private final RestClient http;

    LogoutPropagationImpl(OidcBackchannelSessionIndex index, RegisteredClientRepository clients,
            LogoutTokenFactory tokens, AuditService audit, OrgContext orgContext,
            OrganizationService organizations, LogoutRetryCoordinator retryCoordinator, JdbcTemplate jdbc,
            @Value("${sso.issuer}") String baseIssuer,
            @Value("${sso.oidc.backchannel.http-timeout:PT5S}") Duration timeout) {
        this.index = index;
        this.clients = clients;
        this.tokens = tokens;
        this.audit = audit;
        this.orgContext = orgContext;
        this.organizations = organizations;
        this.retryCoordinator = retryCoordinator;
        this.jdbc = jdbc;
        this.baseIssuer = baseIssuer;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    // @Async on the DEDICATED bounded `logoutPropagationExecutor` (not the shared onboarding pool): the
    // (blocking, per-RP up to `timeout`) fan-out runs off the Spring Session Redis message-listener thread that
    // fires SessionDestroyedEvent — a slow/hung RP no longer serializes every other session's termination
    // propagation. A void @Async failure is surfaced by LoggingAsyncUncaughtExceptionHandler.
    @Override
    @Async("logoutPropagationExecutor")
    public void propagate(String sid, String username) {
        OidcBackchannelSessionIndex.Participants participants = index.lookup(sid);
        String subject = participants.username() != null ? participants.username() : username;
        Set<String> settled = new HashSet<>();
        for (String clientId : participants.clientIds()) {
            BackchannelDeliveryOutcome outcome;
            try {
                UUID clientOrg = clientOrg(clientId);
                String issuer = issuerFor(clientOrg);
                // Run per-client bound to the CLIENT'S tenant, so (a) the org-scoped client registry can see it
                // and (b) the shared JWKSource signs the logout token with that tenant's key — matching the
                // per-tenant issuer stamped on the token, which the RP validates against its own JWKS.
                outcome = clientOrg == null
                        ? sendLogout(clientId, subject, sid, issuer)
                        : orgContext.callInOrg(clientOrg, () -> sendLogout(clientId, subject, sid, issuer));
                audit.record(new AuditRecord(AuditType.OIDC_BACKCHANNEL_LOGOUT,
                        subject, outcome == BackchannelDeliveryOutcome.DELIVERED, "client=" + clientId, null));
            } catch (RuntimeException e) {
                // An IdP-side infra fault (client-org lookup, issuer resolution, audit write, a DB blip mid-fan-out)
                // must NOT drop this client's logout: treat it as TRANSIENT so it stays in the index and the sweep
                // re-drives it, and so the loop can never abort before the reschedule below (which alone makes the
                // termination durable). Never lose a revocation to a transient fault.
                log.warn("back-channel logout for client {} failed to process: {}", clientId, e.getMessage());
                outcome = BackchannelDeliveryOutcome.TRANSIENT;
            }
            if (outcome != BackchannelDeliveryOutcome.TRANSIENT) {
                settled.add(clientId); // delivered or terminally undeliverable — never retry it
            }
        }
        // Clear ONLY what is settled; a transiently-failed client stays in the index for the durable retry
        // sweep, so a temporarily-unreachable RP no longer loses this logout (zero-trust: revocation propagates).
        int remaining = index.removeParticipants(sid, settled);
        retryCoordinator.reschedule(OidcLogoutRetryDriver.RETRY_QUEUE, sid, subject, remaining > 0,
                () -> auditGaveUp(sid, subject));
    }

    // Builds and delivers the logout token to one client, isolating a token-build or network failure so one
    // client never starves the others. TERMINAL (client gone / not a BCL client) is settled without retry;
    // TRANSIENT (endpoint unreachable or token not buildable right now) is kept for the durable retry sweep.
    private BackchannelDeliveryOutcome sendLogout(String clientId, String subject, String sid, String issuer) {
        RegisteredClient client = clients.findByClientId(clientId);
        if (client == null) {
            return BackchannelDeliveryOutcome.TERMINAL;
        }
        Object uri = client.getClientSettings().getSetting(BackChannelLogout.CLIENT_SETTING_URI);
        if (!(uri instanceof String logoutUri) || logoutUri.isBlank()) {
            return BackchannelDeliveryOutcome.TERMINAL; // client not configured for back-channel logout
        }
        boolean sessionRequired = Boolean.TRUE.equals(
                client.getClientSettings().getSetting(BackChannelLogout.CLIENT_SETTING_SESSION_REQUIRED));
        try {
            return post(logoutUri, tokens.create(clientId, subject, sessionRequired ? sid : null, issuer))
                    ? BackchannelDeliveryOutcome.DELIVERED
                    : BackchannelDeliveryOutcome.TRANSIENT;
        } catch (RuntimeException e) {
            log.warn("back-channel logout to {} failed to build/send: {}", clientId, e.getMessage());
            return BackchannelDeliveryOutcome.TRANSIENT;
        }
    }

    // Called once the retry cap is exhausted: the clients still in the index were never delivered. Audit each
    // abandonment so a logout that could not propagate is VISIBLE to operators (A09), never silent, then clear.
    private void auditGaveUp(String sid, String subject) {
        for (String clientId : index.lookup(sid).clientIds()) {
            audit.record(new AuditRecord(AuditType.OIDC_BACKCHANNEL_LOGOUT, subject, false,
                    "client=" + clientId + " abandoned after exhausting retries", null));
        }
        index.clear(sid);
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
