package com.example.sso.federation.internal.application;

import com.example.sso.shared.error.UnauthorizedException;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

/**
 * Validates an upstream id_token (OIDC Core §3.1.3.7). The signature is verified against the provider's JWKS by
 * {@link NimbusJwtDecoder} (which also enforces expiry and rejects an {@code alg=none}/unsigned token — there
 * is no key to verify it with); the issuer, audience (+{@code azp} when multi-valued), nonce and subject are
 * then checked here. Any failure is a generic 401 — an id_token that does not fully validate authenticates no
 * one, and the reason is not disclosed. The JWKS is fetched over a timeout-bounded, redirect-disabled client
 * (symmetric with {@code OidcUpstreamClient}) so a slow or redirecting JWKS host cannot hang the request thread
 * or re-open an SSRF window past the discovery-time host check.
 */
@Component
class IdTokenVerifier {

    private final RestTemplate jwksHttp;

    IdTokenVerifier(@Value("${sso.federation.http-timeout:PT10S}") Duration timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false); // a validated JWKS host must not 302 to an internal one
            }
        };
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.jwksHttp = new RestTemplate(factory);
    }

    VerifiedIdToken verify(OidcMetadata metadata, String clientId, String idToken, String expectedNonce) {
        Jwt jwt = decode(metadata, idToken);

        String issuer = jwt.getIssuer() == null ? null : jwt.getIssuer().toString();
        if (!metadata.issuer().equals(issuer)) {
            throw new UnauthorizedException(); // token minted for/by a different issuer
        }
        List<String> audiences = jwt.getAudience();
        if (audiences == null || !audiences.contains(clientId)) {
            throw new UnauthorizedException(); // not issued to us — audience confusion
        }
        // OIDC §3.1.3.7: with more than one audience, azp MUST be present and equal our client_id.
        if (audiences.size() > 1 && !clientId.equals(jwt.getClaimAsString("azp"))) {
            throw new UnauthorizedException();
        }
        // Binds the token to THIS login attempt: a replayed or injected token carries a different (or no) nonce.
        if (expectedNonce == null || !expectedNonce.equals(jwt.getClaimAsString("nonce"))) {
            throw new UnauthorizedException();
        }
        String subject = jwt.getSubject();
        if (!StringUtils.hasText(subject)) {
            throw new UnauthorizedException(); // sub is REQUIRED — the stable user identifier
        }

        Boolean emailVerified = jwt.getClaimAsBoolean("email_verified");
        return new VerifiedIdToken(subject, jwt.getClaimAsString("email"),
                emailVerified != null && emailVerified, jwt.getClaimAsString("name"));
    }

    private Jwt decode(OidcMetadata metadata, String idToken) {
        try {
            return decoderFor(metadata.jwksUri()).decode(idToken);
        } catch (JwtException e) {
            throw new UnauthorizedException(); // bad signature, expired, or malformed
        }
    }

    /** A JWKS-backed decoder for the provider. Package-visible + overridable so a test can supply a local key. */
    NimbusJwtDecoder decoderFor(String jwksUri) {
        return NimbusJwtDecoder.withJwkSetUri(jwksUri).restOperations(jwksHttp).build();
    }
}
