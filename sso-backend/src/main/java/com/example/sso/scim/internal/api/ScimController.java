package com.example.sso.scim.internal.api;

import de.captaingoldfish.scim.sdk.common.constants.enums.HttpMethod;
import de.captaingoldfish.scim.sdk.common.response.ScimResponse;
import de.captaingoldfish.scim.sdk.server.endpoints.Context;
import de.captaingoldfish.scim.sdk.server.endpoints.ResourceEndpoint;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

/**
 * Single entry point for all SCIM 2.0 traffic under {@code /scim/v2/**}. Delegates routing,
 * validation, and error handling to the scim-sdk {@link ResourceEndpoint}, which invokes
 * the registered {@link UserResourceHandler} / {@link GroupResourceHandler}.
 */
@RestController
@RequiredArgsConstructor
public class ScimController {

    private final ResourceEndpoint resourceEndpoint;

    @RequestMapping("/scim/v2/**")
    public ResponseEntity<String> handle(HttpServletRequest request,
                                         @RequestBody(required = false) String body) {
        String query = request.getQueryString();
        String fullUrl = query == null ? request.getRequestURL().toString()
                : request.getRequestURL() + "?" + query;

        Map<String, String> headers = new HashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name, request.getHeader(name));
        }

        ScimResponse scimResponse = resourceEndpoint.handleRequest(
                fullUrl, HttpMethod.valueOf(request.getMethod()), body, headers, new Context(null));

        HttpHeaders responseHeaders = new HttpHeaders();
        scimResponse.getHttpHeaders().forEach(responseHeaders::add);

        return ResponseEntity.status(scimResponse.getHttpStatus())
                .headers(responseHeaders)
                .body(scimResponse.toString());
    }
}
