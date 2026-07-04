package com.example.sso.oidc.internal.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.oidc.BackChannelLogout;
import com.example.sso.oidc.OidcBackchannelSessionIndex;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
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
    private final RestClient http;

    LogoutPropagationImpl(OidcBackchannelSessionIndex index, RegisteredClientRepository clients,
            LogoutTokenFactory tokens, AuditService audit,
            @Value("${sso.oidc.backchannel.http-timeout:PT5S}") Duration timeout) {
        this.index = index;
        this.clients = clients;
        this.tokens = tokens;
        this.audit = audit;
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
            RegisteredClient client = clients.findByClientId(clientId);
            if (client == null) {
                continue;
            }
            Object uri = client.getClientSettings().getSetting(BackChannelLogout.CLIENT_SETTING_URI);
            if (!(uri instanceof String logoutUri) || logoutUri.isBlank()) {
                continue; // client not configured for back-channel logout
            }
            boolean sessionRequired = Boolean.TRUE.equals(
                    client.getClientSettings().getSetting(BackChannelLogout.CLIENT_SETTING_SESSION_REQUIRED));
            String token = tokens.create(clientId, subject, sessionRequired ? sid : null);
            boolean delivered = post(logoutUri, token);
            audit.record(new AuditRecord(AuditType.OIDC_BACKCHANNEL_LOGOUT, subject, delivered,
                    "client=" + clientId, null));
        }
        index.clear(sid); // idempotency: one dispatch per termination
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
