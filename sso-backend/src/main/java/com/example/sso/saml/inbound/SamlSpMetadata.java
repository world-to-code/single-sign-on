package com.example.sso.saml.inbound;

/**
 * Produces the SAML metadata describing this product's SP role for one federated connection — the document an
 * administrator hands to the upstream IdP's operators so they can configure their side.
 */
public interface SamlSpMetadata {

    /**
     * The SP {@code EntityDescriptor} XML: the entityID, the assertion consumer service, the NameID format this
     * connection requires, and the certificate the upstream verifies our signed AuthnRequests with. Built in the
     * caller's tenant context, so the certificate is that tenant's.
     */
    String document(String spEntityId, String acsUrl, String nameIdFormat);
}
