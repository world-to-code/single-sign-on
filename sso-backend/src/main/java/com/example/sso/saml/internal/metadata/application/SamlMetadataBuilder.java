package com.example.sso.saml.internal.metadata.application;


import com.example.sso.saml.credential.SamlCredentialService;
import net.shibboleth.shared.xml.SerializeSupport;
import org.opensaml.core.xml.config.XMLObjectProviderRegistrySupport;
import org.opensaml.core.xml.io.MarshallingException;
import org.opensaml.saml.common.xml.SAMLConstants;
import org.opensaml.saml.saml2.core.NameID;
import org.opensaml.saml.saml2.metadata.EntityDescriptor;
import org.opensaml.saml.saml2.metadata.IDPSSODescriptor;
import org.opensaml.saml.saml2.metadata.KeyDescriptor;
import org.opensaml.saml.saml2.metadata.NameIDFormat;
import org.opensaml.saml.saml2.metadata.SingleLogoutService;
import org.opensaml.saml.saml2.metadata.SingleSignOnService;
import org.opensaml.security.SecurityException;
import org.opensaml.security.credential.UsageType;
import org.opensaml.security.x509.BasicX509Credential;
import org.opensaml.xmlsec.keyinfo.KeyInfoGenerator;
import org.opensaml.xmlsec.keyinfo.impl.X509KeyInfoGeneratorFactory;
import org.opensaml.xmlsec.signature.KeyInfo;
import org.springframework.stereotype.Service;
import org.w3c.dom.Element;

import static com.example.sso.saml.internal.core.application.SamlObjects.build;

/**
 * Produces the IdP SAML metadata document (EntityDescriptor with an IDPSSODescriptor),
 * publishing the signing certificate and the Redirect/POST SSO endpoints.
 */
@Service
public class SamlMetadataBuilder {

    private final SamlCredentialService credentialService;

    public SamlMetadataBuilder(SamlCredentialService credentialService) {
        this.credentialService = credentialService;
    }

    /**
     * Builds the IdP metadata for {@code idpEntityId} (host-derived), with the SSO/SLO endpoints under it
     * and the current tenant's signing certificate (resolved via {@link SamlCredentialService}).
     */
    public String buildMetadata(String idpEntityId) {
        String ssoLocation = idpEntityId + "/sso";
        String sloLocation = idpEntityId + "/slo";
        try {
            KeyDescriptor keyDescriptor = build(KeyDescriptor.DEFAULT_ELEMENT_NAME);
            keyDescriptor.setUse(UsageType.SIGNING);
            keyDescriptor.setKeyInfo(signingKeyInfo());

            NameIDFormat nameIdFormat = build(NameIDFormat.DEFAULT_ELEMENT_NAME);
            nameIdFormat.setURI(NameID.EMAIL);

            SingleSignOnService redirect = build(SingleSignOnService.DEFAULT_ELEMENT_NAME);
            redirect.setBinding(SAMLConstants.SAML2_REDIRECT_BINDING_URI);
            redirect.setLocation(ssoLocation);
            SingleSignOnService post = build(SingleSignOnService.DEFAULT_ELEMENT_NAME);
            post.setBinding(SAMLConstants.SAML2_POST_BINDING_URI);
            post.setLocation(ssoLocation);

            SingleLogoutService sloRedirect = build(SingleLogoutService.DEFAULT_ELEMENT_NAME);
            sloRedirect.setBinding(SAMLConstants.SAML2_REDIRECT_BINDING_URI);
            sloRedirect.setLocation(sloLocation);
            SingleLogoutService sloPost = build(SingleLogoutService.DEFAULT_ELEMENT_NAME);
            sloPost.setBinding(SAMLConstants.SAML2_POST_BINDING_URI);
            sloPost.setLocation(sloLocation);

            IDPSSODescriptor idpDescriptor = build(IDPSSODescriptor.DEFAULT_ELEMENT_NAME);
            idpDescriptor.addSupportedProtocol(SAMLConstants.SAML20P_NS);
            idpDescriptor.setWantAuthnRequestsSigned(false);
            idpDescriptor.getKeyDescriptors().add(keyDescriptor);
            idpDescriptor.getNameIDFormats().add(nameIdFormat);
            idpDescriptor.getSingleLogoutServices().add(sloRedirect);
            idpDescriptor.getSingleLogoutServices().add(sloPost);
            idpDescriptor.getSingleSignOnServices().add(redirect);
            idpDescriptor.getSingleSignOnServices().add(post);

            EntityDescriptor entityDescriptor = build(EntityDescriptor.DEFAULT_ELEMENT_NAME);
            entityDescriptor.setEntityID(idpEntityId);
            entityDescriptor.getRoleDescriptors().add(idpDescriptor);

            Element element = XMLObjectProviderRegistrySupport.getMarshallerFactory()
                    .getMarshaller(entityDescriptor).marshall(entityDescriptor);
            return SerializeSupport.nodeToString(element);
        } catch (SecurityException | MarshallingException e) {
            throw new IllegalStateException("Failed to build SAML metadata", e);
        }
    }

    private KeyInfo signingKeyInfo() throws SecurityException {
        BasicX509Credential signingCredential =
                new BasicX509Credential(credentialService.getCertificate(), credentialService.getPrivateKey());
        X509KeyInfoGeneratorFactory factory = new X509KeyInfoGeneratorFactory();
        factory.setEmitEntityCertificate(true);
        KeyInfoGenerator generator = factory.newInstance();

        return generator.generate(signingCredential);
    }
}
