package com.example.sso.saml.internal.sso.application;

import com.example.sso.saml.internal.core.application.SamlObjects;
import com.example.sso.saml.internal.credential.application.SamlCertificates;
import com.example.sso.saml.internal.credential.application.SamlSigner;

import com.example.sso.saml.internal.relyingparty.domain.SamlRelyingParty;
import com.example.sso.shared.error.BadRequestException;
import net.shibboleth.shared.security.IdentifierGenerationStrategy;
import net.shibboleth.shared.security.impl.SecureRandomIdentifierGenerationStrategy;
import org.opensaml.core.xml.io.MarshallingException;
import org.opensaml.saml.common.SAMLVersion;
import org.opensaml.saml.saml2.core.*;
import org.opensaml.saml.saml2.encryption.Encrypter;
import org.opensaml.security.SecurityException;
import org.opensaml.security.x509.BasicX509Credential;
import org.opensaml.xmlsec.encryption.support.DataEncryptionParameters;
import org.opensaml.xmlsec.encryption.support.EncryptionConstants;
import org.opensaml.xmlsec.encryption.support.EncryptionException;
import org.opensaml.xmlsec.encryption.support.KeyEncryptionParameters;
import org.opensaml.xmlsec.keyinfo.impl.X509KeyInfoGeneratorFactory;
import org.opensaml.xmlsec.signature.support.SignatureException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;

import static com.example.sso.saml.internal.core.application.SamlObjects.build;
import static com.example.sso.saml.internal.core.application.SamlObjects.stringAttribute;

/**
 * Builds and signs a SAML 2.0 {@link Response} (with a signed {@link Assertion}) for an
 * authenticated user, addressed to a specific relying party. The returned Response is
 * already marshalled and signed (DOM available for encoding).
 */
@Service
public class SamlResponseBuilder {

    private final SamlSigner signer;
    private final long validitySeconds;
    private final IdentifierGenerationStrategy idGenerator = new SecureRandomIdentifierGenerationStrategy();

    public SamlResponseBuilder(SamlSigner signer,
                               @Value("${sso.saml.assertion-validity-seconds:300}") long validitySeconds) {
        this.signer = signer;
        this.validitySeconds = validitySeconds;
    }

    /**
     * Issues a SAML Response for the relying party, applying its per-RP security policy: sign the
     * assertion and/or the response (with the configured algorithm), and optionally encrypt the
     * assertion to the SP's certificate (modern or legacy algorithms). Order matters: sign the
     * assertion first, then encrypt it, then sign the response.
     */
    public Response issueResponse(SamlRelyingParty sp, String inResponseTo, AssertionSubject subject,
                                  String sessionIndex, String idpEntityId) {
        try {
            Response response = buildResponse(sp, inResponseTo, subject, sessionIndex, idpEntityId);
            Assertion assertion = response.getAssertions().get(0);

            if (sp.isSignAssertion()) {
                signer.signObject(assertion, sp.getSignatureAlgorithm());
            }
            if (sp.isEncryptAssertion()) {
                EncryptedAssertion encrypted = encryptAssertion(assertion, sp);
                response.getAssertions().clear();
                response.getEncryptedAssertions().add(encrypted);
            }
            if (sp.isSignResponse()) {
                signer.signObject(response, sp.getSignatureAlgorithm());
            } else {
                signer.marshall(response); // ensure a DOM exists for encoding (reuses a signed assertion's DOM)
            }
            return response;
        } catch (SecurityException | MarshallingException | SignatureException | EncryptionException e) {
            throw new IllegalStateException("Failed to build SAML response", e);
        }
    }

    private Response buildResponse(SamlRelyingParty sp, String inResponseTo,
                                   AssertionSubject assertionSubject, String sessionIndex, String idpEntityId) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(validitySeconds);

        NameID nameId = build(NameID.DEFAULT_ELEMENT_NAME);
        nameId.setValue(assertionSubject.email());
        nameId.setFormat(sp.getNameIdFormat());

        SubjectConfirmationData confirmationData = build(SubjectConfirmationData.DEFAULT_ELEMENT_NAME);
        confirmationData.setRecipient(sp.getAcsUrl());
        confirmationData.setNotOnOrAfter(expiry);
        confirmationData.setInResponseTo(inResponseTo);

        SubjectConfirmation confirmation = build(SubjectConfirmation.DEFAULT_ELEMENT_NAME);
        confirmation.setMethod(SubjectConfirmation.METHOD_BEARER);
        confirmation.setSubjectConfirmationData(confirmationData);

        Subject subject = build(Subject.DEFAULT_ELEMENT_NAME);
        subject.setNameID(nameId);
        subject.getSubjectConfirmations().add(confirmation);

        Audience audience = build(Audience.DEFAULT_ELEMENT_NAME);
        audience.setURI(sp.getEntityId());
        AudienceRestriction audienceRestriction = build(AudienceRestriction.DEFAULT_ELEMENT_NAME);
        audienceRestriction.getAudiences().add(audience);
        Conditions conditions = build(Conditions.DEFAULT_ELEMENT_NAME);
        conditions.setNotBefore(now);
        conditions.setNotOnOrAfter(expiry);
        conditions.getAudienceRestrictions().add(audienceRestriction);

        AuthnContextClassRef classRef = build(AuthnContextClassRef.DEFAULT_ELEMENT_NAME);
        classRef.setURI(AuthnContext.PPT_AUTHN_CTX);
        AuthnContext authnContext = build(AuthnContext.DEFAULT_ELEMENT_NAME);
        authnContext.setAuthnContextClassRef(classRef);
        AuthnStatement authnStatement = build(AuthnStatement.DEFAULT_ELEMENT_NAME);
        authnStatement.setAuthnInstant(now);
        // The OP session id (shared with OIDC) so SLO can target this exact session; random only if absent.
        authnStatement.setSessionIndex(sessionIndex != null ? sessionIndex : idGenerator.generateIdentifier());
        authnStatement.setAuthnContext(authnContext);

        AttributeStatement attributeStatement = build(AttributeStatement.DEFAULT_ELEMENT_NAME);
        attributeStatement.getAttributes().add(stringAttribute("email", assertionSubject.email()));
        if (assertionSubject.displayName() != null) {
            attributeStatement.getAttributes().add(stringAttribute("displayName", assertionSubject.displayName()));
        }
        // `org`: the organization (tenant) id this session logged into — symmetric with the OIDC `org` claim,
        // so a SAML relying party can scope the user to the tenant. Omitted for a global (org-less) session.
        if (assertionSubject.org() != null) {
            attributeStatement.getAttributes().add(stringAttribute("org", assertionSubject.org()));
        }

        Issuer assertionIssuer = build(Issuer.DEFAULT_ELEMENT_NAME);
        assertionIssuer.setValue(idpEntityId);
        Assertion assertion = build(Assertion.DEFAULT_ELEMENT_NAME);
        assertion.setID(idGenerator.generateIdentifier());
        assertion.setIssueInstant(now);
        assertion.setVersion(SAMLVersion.VERSION_20);
        assertion.setIssuer(assertionIssuer);
        assertion.setSubject(subject);
        assertion.setConditions(conditions);
        assertion.getAuthnStatements().add(authnStatement);
        assertion.getAttributeStatements().add(attributeStatement);

        StatusCode statusCode = build(StatusCode.DEFAULT_ELEMENT_NAME);
        statusCode.setValue(StatusCode.SUCCESS);
        Status status = build(Status.DEFAULT_ELEMENT_NAME);
        status.setStatusCode(statusCode);

        Issuer responseIssuer = build(Issuer.DEFAULT_ELEMENT_NAME);
        responseIssuer.setValue(idpEntityId);
        Response response = build(Response.DEFAULT_ELEMENT_NAME);
        response.setID(idGenerator.generateIdentifier());
        response.setIssueInstant(now);
        response.setVersion(SAMLVersion.VERSION_20);
        response.setInResponseTo(inResponseTo);
        response.setDestination(sp.getAcsUrl());
        response.setIssuer(responseIssuer);
        response.setStatus(status);
        response.getAssertions().add(assertion);
        return response;
    }

    /** Encrypts the assertion to the SP's certificate (modern or legacy data/key-transport algorithms). */
    private EncryptedAssertion encryptAssertion(Assertion assertion, SamlRelyingParty sp)
            throws EncryptionException {
        if (sp.getEncryptionCertificate() == null) {
            throw BadRequestException.of("saml.sp.noEncryptionCert");
        }
        BasicX509Credential spCredential = new BasicX509Credential(SamlCertificates.parse(sp.getEncryptionCertificate()));

        DataEncryptionParameters dataParams = new DataEncryptionParameters();
        dataParams.setAlgorithm(dataEncryptionUri(sp.getDataEncryptionAlgorithm()));

        KeyEncryptionParameters keyParams = new KeyEncryptionParameters();
        keyParams.setEncryptionCredential(spCredential);
        keyParams.setAlgorithm(keyTransportUri(sp.getKeyTransportAlgorithm()));
        X509KeyInfoGeneratorFactory keyInfoFactory = new X509KeyInfoGeneratorFactory();
        keyInfoFactory.setEmitEntityCertificate(true);
        keyParams.setKeyInfoGenerator(keyInfoFactory.newInstance());

        Encrypter encrypter = new Encrypter(dataParams, keyParams);
        encrypter.setKeyPlacement(Encrypter.KeyPlacement.INLINE);
        return encrypter.encrypt(assertion);
    }

    private String dataEncryptionUri(String algorithm) {
        return switch (algorithm == null ? "AES256_GCM" : algorithm) {
            case "AES128_GCM" -> EncryptionConstants.ALGO_ID_BLOCKCIPHER_AES128_GCM;
            case "AES256_CBC" -> EncryptionConstants.ALGO_ID_BLOCKCIPHER_AES256;     // legacy CBC
            case "AES128_CBC" -> EncryptionConstants.ALGO_ID_BLOCKCIPHER_AES128;     // legacy CBC
            default -> EncryptionConstants.ALGO_ID_BLOCKCIPHER_AES256_GCM;
        };
    }

    private String keyTransportUri(String algorithm) {
        return switch (algorithm == null ? "RSA_OAEP" : algorithm) {
            case "RSA_1_5" -> EncryptionConstants.ALGO_ID_KEYTRANSPORT_RSA15;        // legacy
            default -> EncryptionConstants.ALGO_ID_KEYTRANSPORT_RSAOAEP;
        };
    }
}
