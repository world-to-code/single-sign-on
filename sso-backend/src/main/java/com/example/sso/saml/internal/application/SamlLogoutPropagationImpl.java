package com.example.sso.saml.internal.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.saml.SamlSloSessionIndex;
import com.example.sso.saml.SloBinding;
import com.example.sso.saml.internal.domain.SamlRelyingParty;
import com.example.sso.saml.internal.domain.SamlRelyingPartyRepository;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Back-channel SAML SLO: for each SOAP-binding participant SP of the terminated session, builds a signed
 * {@code LogoutRequest}, wraps it in a SOAP 1.1 envelope, and POSTs it to the SP's Single Logout endpoint.
 * Per-SP failures are isolated + audited; the mapping is cleared afterwards (one send per termination).
 */
@Service
class SamlLogoutPropagationImpl implements SamlLogoutPropagation {

    private static final Logger log = LoggerFactory.getLogger(SamlLogoutPropagationImpl.class);
    private static final String SOAP_PREFIX =
            "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>";
    private static final String SOAP_SUFFIX = "</soap:Body></soap:Envelope>";

    private final SamlSloSessionIndex index;
    private final SamlRelyingPartyRepository relyingParties;
    private final SamlLogoutMessageBuilder messageBuilder;
    private final AuditService audit;
    private final RestClient http;

    SamlLogoutPropagationImpl(SamlSloSessionIndex index, SamlRelyingPartyRepository relyingParties,
            SamlLogoutMessageBuilder messageBuilder, AuditService audit,
            @Value("${sso.saml.slo.http-timeout:PT5S}") Duration timeout) {
        this.index = index;
        this.relyingParties = relyingParties;
        this.messageBuilder = messageBuilder;
        this.audit = audit;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    @Transactional(readOnly = true)
    public void propagate(String sid, String username) {
        Map<String, String> participants = index.lookup(sid);
        for (Map.Entry<String, String> participant : participants.entrySet()) {
            String entityId = participant.getKey();
            SamlRelyingParty rp = relyingParties.findByEntityId(entityId).orElse(null);
            if (rp == null || !StringUtils.hasText(rp.getSingleLogoutUrl())) {
                continue; // SP not configured for SLO
            }
            // The event-driven (browser-less) path can only reach back-channel SOAP SPs. Front-channel SPs
            // need the explicit-logout redirect chain (not built here) — audit the SKIP so the gap that the
            // SP session survives this termination is VISIBLE to operators, never silent.
            if (rp.sloBinding() != SloBinding.SOAP) {
                audit.record(new AuditRecord(AuditType.SAML_SLO, username, false,
                        "sp=" + entityId + " skipped (front-channel binding, no browser)", null));
                continue;
            }
            // Build + deliver per SP inside the try so one SP's failure never starves the others or skips
            // index.clear below.
            boolean delivered = false;
            try {
                delivered = postSoap(rp.getSingleLogoutUrl(),
                        messageBuilder.signedLogoutRequestXml(rp, participant.getValue(), sid));
            } catch (RuntimeException e) {
                log.warn("SAML SLO to {} failed to build/send: {}", entityId, e.getMessage());
            }
            audit.record(new AuditRecord(AuditType.SAML_SLO, username, delivered, "sp=" + entityId, null));
        }
        index.clear(sid);
    }

    private boolean postSoap(String url, String logoutRequestXml) {
        try {
            http.post().uri(url).contentType(MediaType.TEXT_XML)
                    .body(SOAP_PREFIX + logoutRequestXml + SOAP_SUFFIX).retrieve().toBodilessEntity();
            return true;
        } catch (RestClientException e) {
            log.warn("SAML back-channel LogoutRequest to {} failed: {}", url, e.getMessage());
            return false;
        }
    }
}
