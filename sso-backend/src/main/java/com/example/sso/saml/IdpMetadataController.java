package com.example.sso.saml;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publishes the IdP SAML metadata so service providers can configure trust.
 */
@RestController
public class IdpMetadataController {

    private final SamlMetadataBuilder metadataBuilder;

    public IdpMetadataController(SamlMetadataBuilder metadataBuilder) {
        this.metadataBuilder = metadataBuilder;
    }

    @GetMapping(value = "/saml2/idp/metadata", produces = MediaType.APPLICATION_XML_VALUE)
    public String metadata() {
        return metadataBuilder.buildMetadata();
    }
}
