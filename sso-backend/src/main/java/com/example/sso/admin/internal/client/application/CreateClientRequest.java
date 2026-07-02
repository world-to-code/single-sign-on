package com.example.sso.admin.internal.client.application;

import jakarta.validation.constraints.NotBlank;
import java.util.Set;

/**
 * Admin request to register an OAuth2/OIDC client with full Authorization Server settings.
 * Nulls fall back to sensible defaults. These are AS-side client settings (PKCE, consent,
 * client authentication, token lifetimes/format, signing algorithms, mTLS) — not resource
 * server concerns.
 */
public record CreateClientRequest(
        @NotBlank String clientId,
        String clientName,
        Set<String> redirectUris,
        Set<String> postLogoutRedirectUris,
        Set<String> scopes,
        Set<String> grantTypes,
        Set<String> clientAuthenticationMethods,
        boolean publicClient,
        boolean requireConsent,
        boolean requireProofKey,
        Integer accessTokenMinutes,
        Integer refreshTokenDays,
        Integer authorizationCodeMinutes,
        Integer deviceCodeMinutes,
        boolean reuseRefreshTokens,
        String accessTokenFormat,                 // SELF_CONTAINED | REFERENCE
        String idTokenSignatureAlgorithm,         // e.g. RS256, ES256
        String tokenEndpointAuthSigningAlgorithm, // client_secret_jwt (HS*) / private_key_jwt (RS*/ES*)
        String jwkSetUrl,                         // for private_key_jwt
        String x509SubjectDn,                     // for tls_client_auth
        boolean x509BoundAccessTokens,            // certificate-bound (mTLS) access tokens
        Integer clientSecretDays,                 // secret expiry; null = never
        String initiateLoginUri) {                // OIDC third-party (portal-launch) initiated login URI
}
