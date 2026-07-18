package com.example.sso.saml.internal.logout.application;

import com.example.sso.saml.internal.relyingparty.domain.SamlRelyingParty;
import com.example.sso.tenancy.OrgContext;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Delivers ONE signed SAML {@code LogoutRequest} to a single SOAP-binding SP, bound to the SP's org so the
 * request is signed with that tenant's SAML credential. Shared by the whole-session SLO fan-out
 * ({@link SamlLogoutPropagationImpl}) and the self-service per-app logout ({@link SamlAppSessionSource}) so
 * both build + sign + SOAP-wrap identically.
 */
@Component
class SamlParticipantDelivery {

    private static final Logger log = LoggerFactory.getLogger(SamlParticipantDelivery.class);
    private static final String SOAP_PREFIX =
            "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>";
    private static final String SOAP_SUFFIX = "</soap:Body></soap:Envelope>";

    private final SamlLogoutMessageBuilder messageBuilder;
    private final OrgContext orgContext;
    private final RestClient http;

    SamlParticipantDelivery(SamlLogoutMessageBuilder messageBuilder, OrgContext orgContext,
            @Value("${sso.saml.slo.http-timeout:PT5S}") Duration timeout) {
        this.messageBuilder = messageBuilder;
        this.orgContext = orgContext;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    // Build (which SIGNS with the RP's tenant SAML credential) + deliver, bound to the RP's org so the
    // LogoutRequest is signed with that tenant's key; a global RP (org null) runs in the ambient context.
    boolean sendSoap(SamlRelyingParty rp, String nameId, String sid) {
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
