package com.example.sso.scim.internal.application;

import com.example.sso.scim.internal.api.GroupResourceHandler;
import com.example.sso.scim.internal.api.UserResourceHandler;

import de.captaingoldfish.scim.sdk.common.resources.ServiceProvider;
import de.captaingoldfish.scim.sdk.common.resources.complex.BulkConfig;
import de.captaingoldfish.scim.sdk.common.resources.complex.ChangePasswordConfig;
import de.captaingoldfish.scim.sdk.common.resources.complex.ETagConfig;
import de.captaingoldfish.scim.sdk.common.resources.complex.FilterConfig;
import de.captaingoldfish.scim.sdk.common.resources.complex.PatchConfig;
import de.captaingoldfish.scim.sdk.common.resources.complex.SortConfig;
import de.captaingoldfish.scim.sdk.common.resources.multicomplex.AuthenticationScheme;
import de.captaingoldfish.scim.sdk.server.endpoints.ResourceEndpoint;
import de.captaingoldfish.scim.sdk.server.endpoints.base.GroupEndpointDefinition;
import de.captaingoldfish.scim.sdk.server.endpoints.base.UserEndpointDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Configures the SCIM 2.0 {@link ResourceEndpoint} with the User and Group resource
 * handlers. The base URL / meta.location is derived from the request URL passed to
 * {@code handleRequest}, so no URL extension is configured (not available in scim-sdk 1.33).
 */
@Configuration
public class ScimConfig {

    @Bean
    ResourceEndpoint resourceEndpoint(UserResourceHandler userHandler, GroupResourceHandler groupHandler,
                                      @Value("${sso.scim.max-results:50}") int maxResults,
                                      @Value("${sso.scim.max-filter-depth:5}") int maxFilterDepth,
                                      @Value("${sso.scim.max-bulk-operations:10}") int maxBulkOperations) {
        AuthenticationScheme bearerScheme = AuthenticationScheme.builder()
                .name("OAuth Bearer Token")
                .description("Authentication via the OAuth 2.0 Bearer Token standard")
                .type("oauthbearertoken")
                .specUri("https://www.rfc-editor.org/info/rfc6750")
                .build();

        ServiceProvider serviceProvider = ServiceProvider.builder()
                .patchConfig(PatchConfig.builder().supported(true).build())
                .filterConfig(FilterConfig.builder().supported(true).maxResults(maxResults)
                        .maxFilterDepth(maxFilterDepth).build())
                .sortConfig(SortConfig.builder().supported(true).build())
                .changePasswordConfig(ChangePasswordConfig.builder().supported(false).build())
                .bulkConfig(BulkConfig.builder().supported(true).maxOperations(maxBulkOperations).build())
                .eTagConfig(ETagConfig.builder().supported(false).build())
                .authenticationSchemes(List.of(bearerScheme))
                .build();

        ResourceEndpoint resourceEndpoint = new ResourceEndpoint(serviceProvider);
        resourceEndpoint.registerEndpoint(new UserEndpointDefinition(userHandler));
        resourceEndpoint.registerEndpoint(new GroupEndpointDefinition(groupHandler));
        return resourceEndpoint;
    }
}
