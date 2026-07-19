package com.example.sso.federation.internal.application;

import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.UnauthorizedException;
import com.example.sso.shared.net.OutboundHostValidator;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Talks to an upstream OIDC provider's HTTP endpoints (discovery + token exchange) over an SSRF-guarded,
 * timeout-bounded {@link RestClient}. Every URL's host is re-validated at call time (not just when the issuer
 * was stored) so a provider that passed the write-time check cannot later point discovery or the token
 * endpoint at an internal/metadata address. The JWKS endpoint is validated here and consumed by
 * {@link IdTokenVerifier}. Mirrors {@code OidcBackchannelDelivery}'s outbound-client pattern.
 */
@Component
class OidcUpstreamClient {

    private static final String WELL_KNOWN = "/.well-known/openid-configuration";
    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
            new ParameterizedTypeReference<>() {
            };

    private final OutboundHostValidator hostValidator;
    private final RestClient http;

    OidcUpstreamClient(OutboundHostValidator hostValidator,
            @Value("${sso.federation.http-timeout:PT10S}") Duration timeout) {
        this.hostValidator = hostValidator;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    /** Fetches and validates the discovery document; every endpoint host is SSRF-checked before it is returned. */
    OidcMetadata discover(String issuerUri) {
        validateHost(issuerUri);
        Map<String, Object> doc = get(issuerUri + WELL_KNOWN);
        String issuer = string(doc, "issuer");
        String authorization = string(doc, "authorization_endpoint");
        String token = string(doc, "token_endpoint");
        String jwks = string(doc, "jwks_uri");
        // OIDC Discovery §4.3: the returned issuer MUST exactly equal the requested one — else a rogue document
        // could point auth/token/jwks anywhere while masquerading as the trusted issuer.
        if (!issuerUri.equals(issuer)) {
            throw new BadRequestException("The provider's discovery issuer does not match its configured issuer.");
        }
        validateHost(authorization);
        validateHost(token);
        validateHost(jwks);
        return new OidcMetadata(issuer, authorization, token, jwks);
    }

    /**
     * Exchanges the authorization {@code code} for tokens (PKCE {@code code_verifier}, client_secret_basic auth)
     * and returns the raw id_token. A failed exchange is a generic 401 — no upstream error detail is surfaced.
     */
    String exchangeCodeForIdToken(OidcMetadata metadata, String clientId, String clientSecret, String code,
            String redirectUri, String codeVerifier) {
        validateHost(metadata.tokenEndpoint());
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", redirectUri);
        form.add("code_verifier", codeVerifier);

        Map<String, Object> response;
        try {
            response = http.post().uri(metadata.tokenEndpoint())
                    .header("Authorization", basicAuth(clientId, clientSecret))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(JSON_OBJECT);
        } catch (RestClientException e) {
            throw new UnauthorizedException(); // token endpoint refused / unreachable — do not leak why
        }
        String idToken = response == null ? null : string(response, "id_token");
        if (!StringUtils.hasText(idToken)) {
            throw new UnauthorizedException(); // no id_token → not a usable OIDC response
        }
        return idToken;
    }

    private Map<String, Object> get(String url) {
        try {
            return http.get().uri(url).retrieve().body(JSON_OBJECT);
        } catch (RestClientException e) {
            throw new BadRequestException("The identity provider's discovery endpoint is unreachable.");
        }
    }

    private void validateHost(String url) {
        String host = url == null ? null : URI.create(url).getHost();
        if (host == null) {
            throw new BadRequestException("The provider returned a malformed endpoint URL.");
        }
        hostValidator.validate(host); // SSRF: reject loopback/link-local/metadata/private targets at call time
    }

    private String basicAuth(String clientId, String clientSecret) {
        String creds = clientId + ":" + clientSecret;
        return "Basic " + Base64.getEncoder().encodeToString(creds.getBytes(StandardCharsets.UTF_8));
    }

    private String string(Map<String, Object> doc, String key) {
        Object value = doc.get(key);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new BadRequestException("The provider's discovery document is missing '" + key + "'.");
        }
        return s;
    }
}
