package com.example.sso.saml.internal.inbound.application;

import com.example.sso.saml.credential.SamlCredentialService;
import com.example.sso.saml.inbound.SamlSpMetadata;
import com.example.sso.saml.internal.core.application.SamlObjects;
import lombok.RequiredArgsConstructor;
import org.opensaml.core.xml.config.XMLObjectProviderRegistrySupport;
import org.opensaml.core.xml.io.MarshallingException;
import org.opensaml.saml.common.xml.SAMLConstants;
import org.opensaml.saml.saml2.metadata.AssertionConsumerService;
import org.opensaml.saml.saml2.metadata.EntityDescriptor;
import org.opensaml.saml.saml2.metadata.KeyDescriptor;
import org.opensaml.saml.saml2.metadata.NameIDFormat;
import org.opensaml.saml.saml2.metadata.SPSSODescriptor;
import org.opensaml.security.SecurityException;
import org.opensaml.security.credential.UsageType;
import org.opensaml.security.x509.BasicX509Credential;
import org.opensaml.xmlsec.keyinfo.KeyInfoGenerator;
import org.opensaml.xmlsec.keyinfo.impl.X509KeyInfoGeneratorFactory;
import org.opensaml.xmlsec.signature.KeyInfo;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;

import net.shibboleth.shared.xml.SerializeSupport;

/**
 * Builds the SP-role {@code EntityDescriptor} for one federated connection. The two boolean flags are the
 * document's security contract and are deliberately not configurable: we always sign our AuthnRequests, and we
 * always require the assertion itself to be signed — advertising anything weaker would invite an upstream to
 * send what this product will refuse at the ACS.
 */
@Component
@RequiredArgsConstructor
class SamlSpMetadataBuilder implements SamlSpMetadata {

    private final SamlCredentialService credentialService;

    @Override
    public String document(String spEntityId, String acsUrl, String nameIdFormat) {
        try {
            KeyDescriptor signingKey = SamlObjects.build(KeyDescriptor.DEFAULT_ELEMENT_NAME);
            signingKey.setUse(UsageType.SIGNING);
            signingKey.setKeyInfo(signingKeyInfo());

            NameIDFormat format = SamlObjects.build(NameIDFormat.DEFAULT_ELEMENT_NAME);
            format.setURI(nameIdFormat);

            AssertionConsumerService acs = SamlObjects.build(AssertionConsumerService.DEFAULT_ELEMENT_NAME);
            acs.setBinding(SAMLConstants.SAML2_POST_BINDING_URI);
            acs.setLocation(acsUrl);
            acs.setIndex(0);
            acs.setIsDefault(true);

            SPSSODescriptor descriptor = SamlObjects.build(SPSSODescriptor.DEFAULT_ELEMENT_NAME);
            descriptor.addSupportedProtocol(SAMLConstants.SAML20P_NS);
            descriptor.setAuthnRequestsSigned(true);
            descriptor.setWantAssertionsSigned(true);
            descriptor.getKeyDescriptors().add(signingKey);
            descriptor.getNameIDFormats().add(format);
            descriptor.getAssertionConsumerServices().add(acs);

            EntityDescriptor entity = SamlObjects.build(EntityDescriptor.DEFAULT_ELEMENT_NAME);
            entity.setEntityID(spEntityId);
            entity.getRoleDescriptors().add(descriptor);

            Element element = XMLObjectProviderRegistrySupport.getMarshallerFactory()
                    .getMarshaller(entity).marshall(entity);
            return SerializeSupport.nodeToString(element);
        } catch (SecurityException | MarshallingException e) {
            throw new IllegalStateException("Failed to build SAML SP metadata", e);
        }
    }

    /** The tenant's own signing CERTIFICATE — resolved from the bound org, so a tenant publishes its own key.
     *  Deliberately the certificate-only credential: metadata publishes a public key, so the private one has no
     *  business being read here. */
    private KeyInfo signingKeyInfo() throws SecurityException {
        BasicX509Credential credential = new BasicX509Credential(credentialService.getCertificate());
        X509KeyInfoGeneratorFactory factory = new X509KeyInfoGeneratorFactory();
        factory.setEmitEntityCertificate(true);
        KeyInfoGenerator generator = factory.newInstance();
        return generator.generate(credential);
    }
}
