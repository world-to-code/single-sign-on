package com.example.sso.saml.internal.application;

import com.example.sso.saml.credential.SamlCredentialService;
import org.opensaml.core.xml.XMLObject;
import org.opensaml.core.xml.config.XMLObjectProviderRegistrySupport;
import org.opensaml.core.xml.io.MarshallingException;
import org.opensaml.saml.common.SignableSAMLObject;
import org.opensaml.security.SecurityException;
import org.opensaml.security.x509.BasicX509Credential;
import org.opensaml.xmlsec.crypto.XMLSigningUtil;
import org.opensaml.xmlsec.keyinfo.KeyInfoGenerator;
import org.opensaml.xmlsec.keyinfo.impl.X509KeyInfoGeneratorFactory;
import org.opensaml.xmlsec.signature.KeyInfo;
import org.opensaml.xmlsec.signature.Signature;
import org.opensaml.xmlsec.signature.support.SignatureConstants;
import org.opensaml.xmlsec.signature.support.SignatureException;
import org.opensaml.xmlsec.signature.support.Signer;
import org.springframework.stereotype.Component;

import static com.example.sso.saml.internal.application.SamlObjects.build;

/**
 * Signs SAML objects with the IdP credential — shared by the SSO response builder and the SLO logout-message
 * builder (both Responses and LogoutRequest/Response are {@link SignableSAMLObject}). The credential is
 * resolved fresh each use so key rotation takes effect, and the entity certificate is embedded for the SP.
 */
@Component
public class SamlSigner {

    private final SamlCredentialService credentialService;

    public SamlSigner(SamlCredentialService credentialService) {
        this.credentialService = credentialService;
    }

    /** Signs a SAML object in place (marshalls its tree first) with the given symbolic algorithm. */
    public void signObject(SignableSAMLObject object, String signatureAlgorithm)
            throws SecurityException, MarshallingException, SignatureException {
        Signature signature = newSignature(signatureUri(signatureAlgorithm));
        object.setSignature(signature);
        marshall(object);
        Signer.signObject(signature);
    }

    /** Marshalls an object's DOM (needed before encoding a non-signed message too). */
    public void marshall(XMLObject object) throws MarshallingException {
        XMLObjectProviderRegistrySupport.getMarshallerFactory().getMarshaller(object).marshall(object);
    }

    /** Signs a Redirect-binding query octet-string with the IdP key (detached signature, per SAML). */
    public byte[] signQueryString(byte[] content, String signatureAlgorithm) throws SecurityException {
        return XMLSigningUtil.signWithURI(signingCredential(), signatureUri(signatureAlgorithm), content);
    }

    /** Maps the RP's symbolic signature algorithm to its XML-DSig URI (secure default RSA-SHA256). */
    public String signatureUri(String algorithm) {
        return switch (algorithm == null ? "RSA_SHA256" : algorithm) {
            case "RSA_SHA1" -> SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA1;       // legacy
            case "RSA_SHA512" -> SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA512;
            default -> SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA256;
        };
    }

    private BasicX509Credential signingCredential() {
        return new BasicX509Credential(credentialService.getCertificate(), credentialService.getPrivateKey());
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
}
