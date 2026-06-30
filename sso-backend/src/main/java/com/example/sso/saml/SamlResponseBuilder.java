package com.example.sso.saml;

import com.example.sso.shared.error.BadRequestException;
import net.shibboleth.shared.security.IdentifierGenerationStrategy;
import net.shibboleth.shared.security.impl.SecureRandomIdentifierGenerationStrategy;
import org.opensaml.core.xml.XMLObject;
import org.opensaml.core.xml.config.XMLObjectProviderRegistrySupport;
import org.opensaml.core.xml.io.MarshallingException;
import org.opensaml.saml.common.SAMLVersion;
import org.opensaml.saml.common.SignableSAMLObject;
import org.opensaml.saml.saml2.core.*;
import org.opensaml.saml.saml2.encryption.Encrypter;
import org.opensaml.security.SecurityException;
import org.opensaml.security.x509.BasicX509Credential;
import org.opensaml.xmlsec.encryption.support.DataEncryptionParameters;
import org.opensaml.xmlsec.encryption.support.EncryptionConstants;
import org.opensaml.xmlsec.encryption.support.EncryptionException;
import org.opensaml.xmlsec.encryption.support.KeyEncryptionParameters;
import org.opensaml.xmlsec.keyinfo.KeyInfoGenerator;
import org.opensaml.xmlsec.keyinfo.impl.X509KeyInfoGeneratorFactory;
import org.opensaml.xmlsec.signature.KeyInfo;
import org.opensaml.xmlsec.signature.Signature;
import org.opensaml.xmlsec.signature.support.SignatureConstants;
import org.opensaml.xmlsec.signature.support.SignatureException;
import org.opensaml.xmlsec.signature.support.Signer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;

import static com.example.sso.saml.SamlObjects.build;
import static com.example.sso.saml.SamlObjects.stringAttribute;

/**
 * Builds and signs a SAML 2.0 {@link Response} (with a signed {@link Assertion}) for an
 * authenticated user, addressed to a specific relying party. The returned Response is
 * already marshalled and signed (DOM available for encoding).
 */
@Service
public class SamlResponseBuilder {

    private final SamlCredentialService credentialService;
    private final String idpEntityId;
    private final long validitySeconds;
    private final IdentifierGenerationStrategy idGenerator = new SecureRandomIdentifierGenerationStrategy();

    public SamlResponseBuilder(SamlCredentialService credentialService,
                               @Value("${sso.saml.entity-id}") String idpEntityId,
                               @Value("${sso.saml.assertion-validity-seconds:300}") long validitySeconds) {
        this.credentialService = credentialService;
        this.idpEntityId = idpEntityId;
        this.validitySeconds = validitySeconds;
    }

    /** The IdP signing credential resolved fresh each use (so key rotation takes effect). */
    private BasicX509Credential signingCredential() {
        return new BasicX509Credential(credentialService.getCertificate(), credentialService.getPrivateKey());
    }

    /**
     * Issues a SAML Response for the relying party, applying its per-RP security policy: sign the
     * assertion and/or the response (with the configured algorithm), and optionally encrypt the
     * assertion to the SP's certificate (modern or legacy algorithms). Order matters: sign the
     * assertion first, then encrypt it, then sign the response.
     */
    public Response issueResponse(SamlRelyingParty sp, String inResponseTo, String email, String displayName) {
        try {
            Response response = buildResponse(sp, inResponseTo, email, displayName);
            Assertion assertion = response.getAssertions().get(0);

            if (sp.isSignAssertion()) {
                signObject(assertion, sp.getSignatureAlgorithm());
            }
            if (sp.isEncryptAssertion()) {
                EncryptedAssertion encrypted = encryptAssertion(assertion, sp);
                response.getAssertions().clear();
                response.getEncryptedAssertions().add(encrypted);
            }
            if (sp.isSignResponse()) {
                signObject(response, sp.getSignatureAlgorithm());
            } else {
                marshall(response); // ensure a DOM exists for encoding (reuses a signed assertion's DOM)
            }
            return response;
        } catch (SecurityException | MarshallingException | SignatureException | EncryptionException e) {
            throw new IllegalStateException("Failed to build SAML response", e);
        }
    }

    private Response buildResponse(SamlRelyingParty sp, String inResponseTo,
                                   String email, String displayName) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(validitySeconds);

        NameID nameId = build(NameID.DEFAULT_ELEMENT_NAME);
        nameId.setValue(email);
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
        authnStatement.setSessionIndex(idGenerator.generateIdentifier());
        authnStatement.setAuthnContext(authnContext);

        AttributeStatement attributeStatement = build(AttributeStatement.DEFAULT_ELEMENT_NAME);
        attributeStatement.getAttributes().add(stringAttribute("email", email));
        if (displayName != null) {
            attributeStatement.getAttributes().add(stringAttribute("displayName", displayName));
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

    /** Signs a SAML object (assertion or response) in place; marshalls its tree first. */
    private void signObject(SignableSAMLObject object, String signatureAlgorithm)
            throws SecurityException, MarshallingException, SignatureException {
        Signature signature = newSignature(signatureUri(signatureAlgorithm));
        object.setSignature(signature);
        marshall(object);
        Signer.signObject(signature);
    }

    private void marshall(XMLObject object) throws MarshallingException {
        XMLObjectProviderRegistrySupport.getMarshallerFactory().getMarshaller(object).marshall(object);
    }

    private Signature newSignature(String signatureAlgorithmUri) throws SecurityException {
        BasicX509Credential credential = signingCredential();
        Signature signature = build(Signature.DEFAULT_ELEMENT_NAME);
        signature.setSigningCredential(credential);
        signature.setSignatureAlgorithm(signatureAlgorithmUri);
        signature.setCanonicalizationAlgorithm(SignatureConstants.ALGO_ID_C14N_EXCL_OMIT_COMMENTS);

        X509KeyInfoGeneratorFactory keyInfoFactory = new X509KeyInfoGeneratorFactory();
        keyInfoFactory.setEmitEntityCertificate(true); // embed <ds:X509Certificate> for SP verification
        KeyInfoGenerator keyInfoGenerator = keyInfoFactory.newInstance();
        KeyInfo keyInfo = keyInfoGenerator.generate(credential);
        signature.setKeyInfo(keyInfo);
        return signature;
    }

    /** Encrypts the assertion to the SP's certificate (modern or legacy data/key-transport algorithms). */
    private EncryptedAssertion encryptAssertion(Assertion assertion, SamlRelyingParty sp)
            throws EncryptionException {
        if (sp.getEncryptionCertificate() == null) {
            throw new BadRequestException("encrypt_assertion is enabled but the SP has no encryption certificate");
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

    private static String signatureUri(String algorithm) {
        return switch (algorithm == null ? "RSA_SHA256" : algorithm) {
            case "RSA_SHA1" -> SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA1;       // legacy
            case "RSA_SHA512" -> SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA512;
            default -> SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA256;
        };
    }

    private static String dataEncryptionUri(String algorithm) {
        return switch (algorithm == null ? "AES256_GCM" : algorithm) {
            case "AES128_GCM" -> EncryptionConstants.ALGO_ID_BLOCKCIPHER_AES128_GCM;
            case "AES256_CBC" -> EncryptionConstants.ALGO_ID_BLOCKCIPHER_AES256;     // legacy CBC
            case "AES128_CBC" -> EncryptionConstants.ALGO_ID_BLOCKCIPHER_AES128;     // legacy CBC
            default -> EncryptionConstants.ALGO_ID_BLOCKCIPHER_AES256_GCM;
        };
    }

    private static String keyTransportUri(String algorithm) {
        return switch (algorithm == null ? "RSA_OAEP" : algorithm) {
            case "RSA_1_5" -> EncryptionConstants.ALGO_ID_KEYTRANSPORT_RSA15;        // legacy
            default -> EncryptionConstants.ALGO_ID_KEYTRANSPORT_RSAOAEP;
        };
    }
}
