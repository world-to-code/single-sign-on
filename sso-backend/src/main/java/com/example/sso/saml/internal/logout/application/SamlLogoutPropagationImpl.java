package com.example.sso.saml.internal.logout.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.saml.relyingparty.SloBinding;
import com.example.sso.saml.internal.relyingparty.domain.SamlRelyingParty;
import com.example.sso.saml.internal.relyingparty.domain.SamlRelyingPartyRepository;
import com.example.sso.tenancy.OrgContext;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
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
    private final OrgContext orgContext;
    private final RestClient http;

    SamlLogoutPropagationImpl(SamlSloSessionIndex index, SamlRelyingPartyRepository relyingParties,
            SamlLogoutMessageBuilder messageBuilder, AuditService audit, OrgContext orgContext,
            @Value("${sso.saml.slo.http-timeout:PT5S}") Duration timeout) {
        this.index = index;
        this.relyingParties = relyingParties;
        this.messageBuilder = messageBuilder;
        this.audit = audit;
        this.orgContext = orgContext;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    // NOT @Transactional: this runs on the browser-less expiry path with no OrgContext bound, and each RP is
    // resolved/signed under its OWN tenant scope below — a method-level tx would pin the first connection's
    // RLS GUC and hide every org-scoped RP (they resolve only in platform or their own org's context).
    // @Async on the DEDICATED bounded `logoutPropagationExecutor` (not the shared onboarding pool): offload the
    // blocking SOAP fan-out off the Redis message-listener thread that fires the destroy event, so a slow SP no
    // longer serializes other sessions' propagation (LoggingAsyncUncaughtExceptionHandler surfaces a void
    // failure); it also decouples this listener from the sibling OIDC one on the same event.
    @Override
    @Async("logoutPropagationExecutor")
    public void propagate(String sid, String username) {
        Map<String, String> participants = index.lookup(sid);
        for (Map.Entry<String, String> participant : participants.entrySet()) {
            String entityId = participant.getKey();
            // Resolve cross-tenant (platform context) so an org-scoped RP is visible here, where nothing is
            // bound — RLS would otherwise hide it and the SP session would silently survive this termination.
            SamlRelyingParty rp = orgContext.callAsPlatform(() -> relyingParties.findByEntityId(entityId).orElse(null));
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
                delivered = sendSoapLogout(rp, participant.getValue(), sid);
            } catch (RuntimeException e) {
                log.warn("SAML SLO to {} failed to build/send: {}", entityId, e.getMessage());
            }
            audit.record(new AuditRecord(AuditType.SAML_SLO, username, delivered, "sp=" + entityId, null));
        }
        index.clear(sid);
    }

    // Build (which SIGNS with the RP's tenant SAML credential) + deliver, bound to the RP's org so the
    // LogoutRequest is signed with that tenant's key; a global RP (org null) runs in the ambient context.
    private boolean sendSoapLogout(SamlRelyingParty rp, String nameId, String sid) {
        UUID org = rp.getOrgId();
        if (org == null) {
            return postSoap(rp.getSingleLogoutUrl(), messageBuilder.signedLogoutRequestXml(rp, nameId, sid));
        }
        return orgContext.callInOrg(org, () ->
                postSoap(rp.getSingleLogoutUrl(), messageBuilder.signedLogoutRequestXml(rp, nameId, sid)));
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
