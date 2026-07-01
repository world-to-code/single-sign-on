package com.example.sso.saml.internal.api;

import com.example.sso.saml.internal.application.SamlMetadataBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publishes the IdP SAML metadata so service providers can configure trust.
 */
@RestController
@RequiredArgsConstructor
public class IdpMetadataController {

    private final SamlMetadataBuilder metadataBuilder;

    @GetMapping(value = "/saml2/idp/metadata", produces = MediaType.APPLICATION_XML_VALUE)
    public String metadata() {
        return metadataBuilder.buildMetadata();
    }
}
