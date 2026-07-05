package com.example.sso.saml.internal.api;

import com.example.sso.saml.internal.application.SamlMetadataService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publishes the IdP SAML metadata (entityID + endpoints + signing certificate) for the request host, so a
 * tenant's service providers configure trust against that tenant's own host-scoped IdP.
 */
@RestController
@RequiredArgsConstructor
public class IdpMetadataController {

    private final SamlMetadataService metadataService;

    @GetMapping(value = "/saml2/idp/metadata", produces = MediaType.APPLICATION_XML_VALUE)
    public String metadata(HttpServletRequest request) {
        return metadataService.metadataFor(request);
    }
}
