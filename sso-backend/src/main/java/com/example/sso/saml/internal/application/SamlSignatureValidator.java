package com.example.sso.saml.internal.application;

import com.example.sso.saml.internal.domain.SamlRelyingParty;
import com.example.sso.shared.error.BadRequestException;
import org.opensaml.saml.common.SignableSAMLObject;
import org.opensaml.saml.security.impl.SAMLSignatureProfileValidator;
import org.opensaml.security.SecurityException;
import org.opensaml.security.x509.BasicX509Credential;
import org.opensaml.xmlsec.crypto.XMLSigningUtil;
import org.opensaml.xmlsec.signature.Signature;
import org.opensaml.xmlsec.signature.support.SignatureException;
import org.opensaml.xmlsec.signature.support.SignatureValidator;
import org.springframework.stereotype.Service;

import java.security.cert.X509Certificate;

/**
 * Verifies SP-signed SAML AuthnRequests against the relying party's registered signing certificate
 * — embedded XML signatures (POST binding) and detached query-string signatures (Redirect binding).
 */
@Service
public class SamlSignatureValidator {

    /** Verifies an embedded {@code <ds:Signature>} on a POST-binding AuthnRequest. */
    public void verifyEmbedded(SignableSAMLObject signed, SamlRelyingParty relyingParty) {
        Signature signature = signed.getSignature();
        if (signature == null) {
            throw BadRequestException.of("saml.authnRequest.signatureRequired");
        }

        BasicX509Credential credential = new BasicX509Credential(requireCertificate(relyingParty));
        try {
            new SAMLSignatureProfileValidator().validate(signature);
            SignatureValidator.validate(signature, credential);
        } catch (SignatureException e) {
            throw BadRequestException.of("saml.authnRequest.signatureInvalid");
        }
    }

    /** Verifies a Redirect-binding detached signature over the raw signed query string. */
    public void verifyRedirect(byte[] signedContent, String signatureAlgorithmUri, byte[] signature,
                               SamlRelyingParty relyingParty) {
        BasicX509Credential credential = new BasicX509Credential(requireCertificate(relyingParty));
        try {
            if (!XMLSigningUtil.verifyWithURI(credential, signatureAlgorithmUri, signature, signedContent)) {
                throw BadRequestException.of("saml.authnRequest.signatureInvalid");
            }
        } catch (SecurityException e) {
            throw BadRequestException.of("saml.authnRequest.signatureUnverifiable");
        }
    }

    private X509Certificate requireCertificate(SamlRelyingParty relyingParty) {
        if (relyingParty.getSigningCertificate() == null) {
            throw BadRequestException.of("saml.sp.noSigningCert");
        }

        return SamlCertificates.parse(relyingParty.getSigningCertificate());
    }
}
