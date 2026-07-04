package com.example.sso.saml.internal.application;

import com.example.sso.saml.internal.domain.SamlRelyingParty;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;

import static com.example.sso.saml.internal.application.SamlObjects.build;

/**
 * Builds and signs a SAML 2.0 {@link LogoutRequest} for a relying party (with the user's NameID and the
 * OP SessionIndex), serialized to XML for delivery. Signs with the shared {@link SamlSigner} (same IdP key
 * the SP already trusts for SSO), so the SP verifies it against the IdP metadata certificate.
 */
@Component
public class SamlLogoutMessageBuilder {

    private final SamlSigner signer;
    private final String idpEntityId;
    private final IdentifierGenerationStrategy idGenerator = new SecureRandomIdentifierGenerationStrategy();

    public SamlLogoutMessageBuilder(SamlSigner signer, @Value("${sso.saml.entity-id}") String idpEntityId) {
        this.signer = signer;
        this.idpEntityId = idpEntityId;
    }

    /** A signed LogoutRequest addressed to the SP, serialized to an XML element string (no XML declaration). */
    public String signedLogoutRequestXml(SamlRelyingParty sp, String nameIdValue, String sessionIndex) {
        try {
            LogoutRequest request = build(LogoutRequest.DEFAULT_ELEMENT_NAME);
            request.setID(idGenerator.generateIdentifier());
            request.setIssueInstant(Instant.now());
            request.setVersion(SAMLVersion.VERSION_20);
            request.setDestination(sp.getSingleLogoutUrl());

            Issuer issuer = build(Issuer.DEFAULT_ELEMENT_NAME);
            issuer.setValue(idpEntityId);
            request.setIssuer(issuer);

            NameID nameId = build(NameID.DEFAULT_ELEMENT_NAME);
            nameId.setValue(nameIdValue);
            nameId.setFormat(sp.getNameIdFormat());
            request.setNameID(nameId);

            SessionIndex sessionIndexElement = build(SessionIndex.DEFAULT_ELEMENT_NAME);
            sessionIndexElement.setValue(sessionIndex);
            request.getSessionIndexes().add(sessionIndexElement);

            signer.signObject(request, sp.getSignatureAlgorithm());
            return SerializeSupport.nodeToString(request.getDOM());
        } catch (SecurityException | MarshallingException | SignatureException e) {
            throw new IllegalStateException("Failed to build SAML LogoutRequest", e);
        }
    }

    /** A signed successful LogoutResponse answering the SP's LogoutRequest ({@code inResponseTo} = its ID). */
    public LogoutResponse signedLogoutResponse(SamlRelyingParty sp, String inResponseTo) {
        try {
            LogoutResponse response = build(LogoutResponse.DEFAULT_ELEMENT_NAME);
            response.setID(idGenerator.generateIdentifier());
            response.setIssueInstant(Instant.now());
            response.setVersion(SAMLVersion.VERSION_20);
            response.setInResponseTo(inResponseTo);
            response.setDestination(sp.getSingleLogoutUrl());

            Issuer issuer = build(Issuer.DEFAULT_ELEMENT_NAME);
            issuer.setValue(idpEntityId);
            response.setIssuer(issuer);

            StatusCode statusCode = build(StatusCode.DEFAULT_ELEMENT_NAME);
            statusCode.setValue(StatusCode.SUCCESS);
            Status status = build(Status.DEFAULT_ELEMENT_NAME);
            status.setStatusCode(statusCode);
            response.setStatus(status);

            signer.signObject(response, sp.getSignatureAlgorithm());
            return response;
        } catch (SecurityException | MarshallingException | SignatureException e) {
            throw new IllegalStateException("Failed to build SAML LogoutResponse", e);
        }
    }
}
