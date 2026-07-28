package com.example.sso.saml.internal.inbound.application;

import com.example.sso.saml.inbound.AssertionExpectations;
import com.example.sso.saml.inbound.SamlAuthnRequest;
import com.example.sso.saml.inbound.SamlSpProtocol;
import com.example.sso.saml.inbound.UpstreamIdp;
import com.example.sso.saml.inbound.VerifiedAssertion;
import com.example.sso.saml.internal.core.application.SamlBindingCodec;
import com.example.sso.saml.internal.core.application.SamlObjects;
import com.example.sso.saml.internal.core.application.SamlRedirectEncoder;
import com.example.sso.saml.internal.credential.application.SamlCertificates;
import com.example.sso.saml.internal.credential.application.SamlSigner;
import com.example.sso.shared.error.UnauthorizedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import net.shibboleth.shared.xml.SerializeSupport;
import org.opensaml.core.xml.XMLObject;
import org.opensaml.core.xml.io.MarshallingException;
import org.opensaml.core.xml.schema.XSAny;
import org.opensaml.core.xml.schema.XSString;
import org.opensaml.saml.common.SAMLVersion;
import org.opensaml.saml.common.xml.SAMLConstants;
import org.opensaml.saml.saml2.core.Assertion;
import org.opensaml.saml.saml2.core.Attribute;
import org.opensaml.saml.saml2.core.AttributeStatement;
import org.opensaml.saml.saml2.core.Audience;
import org.opensaml.saml.saml2.core.AudienceRestriction;
import org.opensaml.saml.saml2.core.AuthnRequest;
import org.opensaml.saml.saml2.core.Conditions;
import org.opensaml.saml.saml2.core.Issuer;
import org.opensaml.saml.saml2.core.NameID;
import org.opensaml.saml.saml2.core.NameIDPolicy;
import org.opensaml.saml.saml2.core.Response;
import org.opensaml.saml.saml2.core.StatusCode;
import org.opensaml.saml.saml2.core.SubjectConfirmation;
import org.opensaml.saml.saml2.core.SubjectConfirmationData;
import org.opensaml.saml.security.impl.SAMLSignatureProfileValidator;
import org.opensaml.security.x509.BasicX509Credential;
import org.opensaml.xmlsec.signature.support.SignatureValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * The SP side of the wire. Everything here is written to the rule that an assertion is a bearer credential
 * handed to us by the ATTACKER-CONTROLLABLE browser: nothing is trusted because it appears in the document, and
 * every value that is later acted upon is read from the one assertion whose signature verified.
 *
 * <p>The refusals are deliberately indistinguishable — one {@link UnauthorizedException}, no message keyed to
 * which check failed. A verifier that says "wrong audience" versus "bad signature" is an oracle for probing the
 * connection's configuration.
 */
@Component
@RequiredArgsConstructor
class SamlSpProtocolImpl implements SamlSpProtocol {

    private final SamlBindingCodec codec;
    private final SamlSigner signer;
    private final SamlRedirectEncoder redirectEncoder;
    private final Clock clock;

    /** Tolerance for clock drift between this product and the upstream, both directions. */
    @Value("${sso.saml.inbound.clock-skew}")
    private Duration clockSkew;

    @Value("${sso.saml.inbound.signature-algorithm}")
    private String signatureAlgorithm;

    @Override
    public SamlAuthnRequest buildAuthnRequest(UpstreamIdp upstream, String spEntityId, String acsUrl,
            String relayState) {
        String requestId = "_" + UUID.randomUUID(); // xsd:ID may not start with a digit
        AuthnRequest request = SamlObjects.build(AuthnRequest.DEFAULT_ELEMENT_NAME);
        request.setID(requestId);
        request.setVersion(SAMLVersion.VERSION_20);
        request.setIssueInstant(clock.instant());
        request.setDestination(upstream.ssoUrl());
        request.setProtocolBinding(SAMLConstants.SAML2_POST_BINDING_URI);
        request.setAssertionConsumerServiceURL(acsUrl);

        Issuer issuer = SamlObjects.build(Issuer.DEFAULT_ELEMENT_NAME);
        issuer.setValue(spEntityId);
        request.setIssuer(issuer);

        // Ask for the format the connection was configured with. The ACS enforces it too — asking is a courtesy
        // to the upstream, enforcing is the control.
        NameIDPolicy policy = SamlObjects.build(NameIDPolicy.DEFAULT_ELEMENT_NAME);
        policy.setFormat(upstream.nameIdFormat());
        policy.setAllowCreate(true);
        request.setNameIDPolicy(policy);

        String redirect = redirectEncoder.encodeRequest(upstream.ssoUrl(), toXml(request), relayState,
                signatureAlgorithm);
        return new SamlAuthnRequest(redirect, requestId);
    }

    @Override
    public VerifiedAssertion verify(String base64Response, AssertionExpectations expect) {
        Response response = codec.decodeResponsePost(base64Response);
        requireSuccess(response);
        requireMatches(response.getDestination(), expect.acsUrl(), true);

        Assertion assertion = onlyAssertion(response);
        // Signature FIRST, and on the ASSERTION rather than only the Response: a signed envelope wrapped around
        // an unsigned (or foreign) assertion is the classic signature-wrapping attack. The profile validator runs
        // before the cryptographic check because it is what refuses the reference tricks that let one signature
        // appear to cover a different element than the one we are about to read.
        verifySignature(assertion, expect.signingCertificate());

        requireMatches(issuerOf(assertion), expect.idpEntityId(), false);
        requireAudience(assertion, expect.spEntityId());
        requireValidityWindow(assertion);
        requireSubjectConfirmation(assertion, expect);
        NameID nameId = requireNameId(assertion, expect.nameIdFormat());

        return new VerifiedAssertion(assertion.getID(), nameId.getValue(),
                notOnOrAfterOf(assertion), attributesOf(assertion));
    }

    /** The AuthnRequest is signed as a DETACHED query-string signature by the Redirect binding, so the document
     *  itself only needs marshalling. */
    private String toXml(AuthnRequest request) {
        try {
            signer.marshall(request);
            return SerializeSupport.nodeToString(request.getDOM());
        } catch (MarshallingException e) {
            throw new IllegalStateException("Failed to marshal the AuthnRequest", e);
        }
    }

    private void requireSuccess(Response response) {
        StatusCode code = response.getStatus() == null ? null : response.getStatus().getStatusCode();
        if (code == null || !StatusCode.SUCCESS.equals(code.getValue())) {
            throw new UnauthorizedException();
        }
    }

    /**
     * Exactly one assertion, and never an encrypted one. Multiple assertions are the other half of the wrapping
     * family — we would verify one and read another — and this product has no decrypter, so an EncryptedAssertion
     * must be refused loudly rather than silently ignored while some other element is trusted.
     */
    private Assertion onlyAssertion(Response response) {
        if (!response.getEncryptedAssertions().isEmpty() || response.getAssertions().size() != 1) {
            throw new UnauthorizedException();
        }
        return response.getAssertions().get(0);
    }

    private void verifySignature(Assertion assertion, String signingCertificate) {
        if (assertion.getSignature() == null) {
            throw new UnauthorizedException(); // an unsigned assertion asserts nothing
        }
        try {
            new SAMLSignatureProfileValidator().validate(assertion.getSignature());
            SignatureValidator.validate(assertion.getSignature(),
                    new BasicX509Credential(SamlCertificates.parse(signingCertificate)));
        } catch (Exception invalid) {
            throw new UnauthorizedException();
        }
    }

    private void requireAudience(Assertion assertion, String spEntityId) {
        Conditions conditions = assertion.getConditions();
        if (conditions == null || conditions.getAudienceRestrictions().isEmpty()) {
            throw new UnauthorizedException(); // an assertion for nobody in particular is an assertion for anyone
        }
        boolean addressedToUs = conditions.getAudienceRestrictions().stream()
                .map(AudienceRestriction::getAudiences)
                .flatMap(List::stream)
                .map(Audience::getURI)
                .anyMatch(spEntityId::equals);
        if (!addressedToUs) {
            throw new UnauthorizedException();
        }
    }

    private void requireValidityWindow(Assertion assertion) {
        Conditions conditions = assertion.getConditions();
        Instant now = clock.instant();
        if (conditions == null
                || (conditions.getNotBefore() != null && now.isBefore(conditions.getNotBefore().minus(clockSkew)))
                || (conditions.getNotOnOrAfter() != null
                        && !now.isBefore(conditions.getNotOnOrAfter().plus(clockSkew)))) {
            throw new UnauthorizedException();
        }
    }

    /**
     * The bearer subject confirmation binds the assertion to THIS delivery: our endpoint, our in-flight request,
     * and a moment that has not passed. Without {@code InResponseTo} an unsolicited assertion — one the attacker
     * asked the upstream for, addressed to a victim — would be accepted.
     */
    private void requireSubjectConfirmation(Assertion assertion, AssertionExpectations expect) {
        if (assertion.getSubject() == null) {
            throw new UnauthorizedException();
        }
        boolean confirmed = assertion.getSubject().getSubjectConfirmations().stream()
                .filter(confirmation -> SubjectConfirmation.METHOD_BEARER.equals(confirmation.getMethod()))
                .map(SubjectConfirmation::getSubjectConfirmationData)
                .anyMatch(data -> bearerDataMatches(data, expect));
        if (!confirmed) {
            throw new UnauthorizedException();
        }
    }

    private boolean bearerDataMatches(SubjectConfirmationData data, AssertionExpectations expect) {
        return data != null
                && expect.acsUrl().equals(data.getRecipient())
                && expect.inResponseTo().equals(data.getInResponseTo())
                && data.getNotOnOrAfter() != null
                && clock.instant().isBefore(data.getNotOnOrAfter().plus(clockSkew));
    }

    private NameID requireNameId(Assertion assertion, String expectedFormat) {
        NameID nameId = assertion.getSubject().getNameID();
        if (nameId == null || !StringUtils.hasText(nameId.getValue())) {
            throw new UnauthorizedException();
        }
        // The format is part of the join key's contract: a connection configured for a stable identifier must
        // not silently start receiving a reassignable address. An absent format is not "whatever we asked for".
        if (!expectedFormat.equals(nameId.getFormat())) {
            throw new UnauthorizedException();
        }
        return nameId;
    }

    private Instant notOnOrAfterOf(Assertion assertion) {
        Conditions conditions = assertion.getConditions();
        return conditions == null || conditions.getNotOnOrAfter() == null
                ? clock.instant().plus(clockSkew) : conditions.getNotOnOrAfter();
    }

    private String issuerOf(Assertion assertion) {
        return assertion.getIssuer() == null ? null : assertion.getIssuer().getValue();
    }

    /** Equality against an expected value; {@code optional} tolerates the field being absent from the document. */
    private void requireMatches(String actual, String expected, boolean optional) {
        if (actual == null ? !optional : !actual.equals(expected)) {
            throw new UnauthorizedException();
        }
    }

    /** First string value per attribute name, read from the SIGNED assertion. */
    private Map<String, String> attributesOf(Assertion assertion) {
        Map<String, String> attributes = new HashMap<>();
        for (AttributeStatement statement : assertion.getAttributeStatements()) {
            for (Attribute attribute : statement.getAttributes()) {
                firstStringValue(attribute).ifPresent(value ->
                        attributes.putIfAbsent(attribute.getName(), value));
            }
        }
        return Map.copyOf(attributes);
    }

    /**
     * Both shapes an AttributeValue arrives in. OpenSAML unmarshals it as {@link XSString} only when the element
     * carries {@code xsi:type="xs:string"}; without one — which is what Shibboleth, ADFS and Entra actually send
     * — it is an {@link XSAny}, and reading only the typed shape silently drops EVERY attribute. That is how it
     * shipped, invisible to tests that hand the caller a ready-made map instead of parsing XML.
     */
    private Optional<String> firstStringValue(Attribute attribute) {
        for (XMLObject value : attribute.getAttributeValues()) {
            String text = switch (value) {
                case XSString typed -> typed.getValue();
                // The element's OWN text, not its descendants': getTextContent would splice in the content of
                // any child an upstream nested here, which is a value the tenant never sees in the assertion.
                case XSAny untyped -> untyped.getTextContent();
                default -> null;
            };
            if (StringUtils.hasText(text)) {
                return Optional.of(text.trim());
            }
        }
        return Optional.empty();
    }
}
