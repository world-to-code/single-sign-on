package com.example.sso.saml.internal.logout.application;

import com.example.sso.saml.internal.core.application.SamlEntityId;
import com.example.sso.saml.internal.credential.application.SamlSigner;

import com.example.sso.saml.internal.relyingparty.domain.SamlRelyingParty;
import net.shibboleth.shared.security.IdentifierGenerationStrategy;
import net.shibboleth.shared.security.impl.SecureRandomIdentifierGenerationStrategy;
import net.shibboleth.shared.xml.SerializeSupport;
import org.opensaml.core.xml.io.MarshallingException;
import org.opensaml.saml.common.SAMLVersion;
import org.opensaml.saml.saml2.core.Issuer;
import org.opensaml.saml.saml2.core.LogoutRequest;
import org.opensaml.saml.saml2.core.LogoutResponse;
import org.opensaml.saml.saml2.core.NameID;
import org.opensaml.saml.saml2.core.SessionIndex;
import org.opensaml.saml.saml2.core.Status;
import org.opensaml.saml.saml2.core.StatusCode;
import org.opensaml.security.SecurityException;
import org.opensaml.xmlsec.signature.support.SignatureException;
import org.springframework.stereotype.Component;

import java.time.Instant;

import static com.example.sso.saml.internal.core.application.SamlObjects.build;

/**
 * Builds and signs a SAML 2.0 {@link LogoutRequest} for a relying party (with the user's NameID and the
 * OP SessionIndex), serialized to XML for delivery. Signs with the shared {@link SamlSigner} (same IdP key
 * the SP already trusts for SSO), so the SP verifies it against the IdP metadata certificate.
 */
@Component
public class SamlLogoutMessageBuilder {

    private final SamlSigner signer;
    private final SamlEntityId entityId;
    private final IdentifierGenerationStrategy idGenerator = new SecureRandomIdentifierGenerationStrategy();

    public SamlLogoutMessageBuilder(SamlSigner signer, SamlEntityId entityId) {
        this.signer = signer;
        this.entityId = entityId;
    }

    /**
     * The entityID an SLO message to {@code sp} is issued under: the SP's own tenant entityID, matching the
     * issuer its SSO assertions carried (and the one it registered). Signing already uses that tenant's key,
     * so a platform Issuer here would be an SP-side mismatch.
     */
    private String issuerFor(SamlRelyingParty sp) {
        return entityId.forOrg(sp.getOrgId());
    }

    private LogoutRequest buildLogoutRequest(SamlRelyingParty sp, String nameIdValue, String sessionIndex) {
        LogoutRequest request = build(LogoutRequest.DEFAULT_ELEMENT_NAME);
        request.setID(idGenerator.generateIdentifier());
        request.setIssueInstant(Instant.now());
        request.setVersion(SAMLVersion.VERSION_20);
        request.setDestination(sp.getSingleLogoutUrl());

        Issuer issuer = build(Issuer.DEFAULT_ELEMENT_NAME);
        issuer.setValue(issuerFor(sp));
        request.setIssuer(issuer);

        NameID nameId = build(NameID.DEFAULT_ELEMENT_NAME);
        nameId.setValue(nameIdValue);
        nameId.setFormat(sp.getNameIdFormat());
        request.setNameID(nameId);

        SessionIndex sessionIndexElement = build(SessionIndex.DEFAULT_ELEMENT_NAME);
        sessionIndexElement.setValue(sessionIndex);
        request.getSessionIndexes().add(sessionIndexElement);
        return request;
    }

    /** A signed LogoutRequest (embedded signature — for SOAP/POST bindings), serialized to an XML string. */
    public String signedLogoutRequestXml(SamlRelyingParty sp, String nameIdValue, String sessionIndex) {
        try {
            LogoutRequest request = buildLogoutRequest(sp, nameIdValue, sessionIndex);
            signer.signObject(request, sp.getSignatureAlgorithm());
            return SerializeSupport.nodeToString(request.getDOM());
        } catch (SecurityException | MarshallingException | SignatureException e) {
            throw new IllegalStateException("Failed to build SAML LogoutRequest", e);
        }
    }

    /** An UNSIGNED LogoutRequest XML string — for the Redirect binding, where the signature is on the query. */
    public String unsignedLogoutRequestXml(SamlRelyingParty sp, String nameIdValue, String sessionIndex) {
        try {
            LogoutRequest request = buildLogoutRequest(sp, nameIdValue, sessionIndex);
            signer.marshall(request);
            return SerializeSupport.nodeToString(request.getDOM());
        } catch (MarshallingException e) {
            throw new IllegalStateException("Failed to build SAML LogoutRequest", e);
        }
    }

    /**
     * A signed LogoutResponse answering the SP's LogoutRequest ({@code inResponseTo} = its ID). When nothing
     * was terminated — the request named a SessionIndex this IdP no longer holds — the status is
     * {@code Responder}/{@code PartialLogout}, NOT Success: an SP told "Success" records a completed global
     * logout while the IdP session (and its SSO to every other relying party) lives on.
     */
    public LogoutResponse signedLogoutResponse(SamlRelyingParty sp, String inResponseTo, boolean terminated) {
        try {
            LogoutResponse response = build(LogoutResponse.DEFAULT_ELEMENT_NAME);
            response.setID(idGenerator.generateIdentifier());
            response.setIssueInstant(Instant.now());
            response.setVersion(SAMLVersion.VERSION_20);
            response.setInResponseTo(inResponseTo);
            response.setDestination(sp.getSingleLogoutUrl());

            Issuer issuer = build(Issuer.DEFAULT_ELEMENT_NAME);
            issuer.setValue(issuerFor(sp));
            response.setIssuer(issuer);

            Status status = build(Status.DEFAULT_ELEMENT_NAME);
            StatusCode statusCode = build(StatusCode.DEFAULT_ELEMENT_NAME);
            statusCode.setValue(terminated ? StatusCode.SUCCESS : StatusCode.RESPONDER);
            if (!terminated) {
                StatusCode partial = build(StatusCode.DEFAULT_ELEMENT_NAME);
                partial.setValue(StatusCode.PARTIAL_LOGOUT);
                statusCode.setStatusCode(partial);
            }
            status.setStatusCode(statusCode);
            response.setStatus(status);

            signer.signObject(response, sp.getSignatureAlgorithm());
            return response;
        } catch (SecurityException | MarshallingException | SignatureException e) {
            throw new IllegalStateException("Failed to build SAML LogoutResponse", e);
        }
    }
}
