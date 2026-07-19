package com.example.sso.federation.internal.application;

import com.example.sso.federation.FederatedIdentity;
import com.example.sso.federation.FederationAuthorization;
import com.example.sso.federation.FederationLoginService;
import com.example.sso.federation.FederationProvider;
import com.example.sso.tenancy.OrgContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Orchestrates an inbound OIDC login: resolves the tenant's provider (RLS-scoped via {@code callInOrg}, since
 * this runs pre-authentication with no bound OrgContext), discovers the upstream, builds the PKCE authorization
 * request, and on callback exchanges the code and validates the id_token into a {@link FederatedIdentity}. The
 * DB read and the network round-trips are kept apart (no connection held across the wire). Secrets stay in the
 * module: the decrypted secret is used only to call the token endpoint and never returned.
 */
@Service
@RequiredArgsConstructor
public class FederationLoginServiceImpl implements FederationLoginService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
    private static final String PKCE_METHOD = "S256";

    private final FederationConfigStore configStore;
    private final OrgContext orgContext;
    private final OidcUpstreamClient upstream;
    private final IdTokenVerifier verifier;

    @Override
    public List<FederationProvider> enabledProviders(UUID orgId) {
        return orgContext.callInOrg(orgId, () -> configStore.enabled(orgId));
    }

    @Override
    public FederationAuthorization beginLogin(UUID orgId, String alias, String redirectUri) {
        ResolvedProvider provider = load(orgId, alias);
        OidcMetadata metadata = upstream.discover(provider.issuerUri());

        String state = randomToken();
        String nonce = randomToken();
        String codeVerifier = randomToken();
        String authorizationUri = UriComponentsBuilder.fromUriString(metadata.authorizationEndpoint())
                .queryParam("response_type", "code")
                .queryParam("client_id", provider.clientId())
                .queryParam("redirect_uri", redirectUri)
                .queryParam("scope", scopesOrDefault(provider.scopes()))
                .queryParam("state", state)
                .queryParam("nonce", nonce)
                .queryParam("code_challenge", codeChallenge(codeVerifier))
                .queryParam("code_challenge_method", PKCE_METHOD)
                .encode()
                .build()
                .toUriString();
        return new FederationAuthorization(authorizationUri, state, nonce, codeVerifier);
    }

    @Override
    public FederatedIdentity completeLogin(UUID orgId, String alias, String code, String redirectUri, String nonce,
            String codeVerifier) {
        ResolvedProvider provider = load(orgId, alias);
        OidcMetadata metadata = upstream.discover(provider.issuerUri());
        String idToken = upstream.exchangeCodeForIdToken(metadata, provider.clientId(), provider.clientSecret(),
                code, redirectUri, codeVerifier);
        VerifiedIdToken claims = verifier.verify(metadata, provider.clientId(), idToken, nonce);
        return new FederatedIdentity(provider.alias(), provider.issuerUri(), claims.subject(), claims.email(),
                claims.emailVerified(), claims.name(), provider.jitProvisioningAllowed());
    }

    /** Reads the provider inside the tenant's RLS context (pre-auth has none bound by the request filter). */
    private ResolvedProvider load(UUID orgId, String alias) {
        return orgContext.callInOrg(orgId, () -> configStore.resolveEnabled(orgId, alias));
    }

    private String scopesOrDefault(String scopes) {
        return scopes == null || scopes.isBlank() ? OidcScopes.OPENID : scopes;
    }

    /** A 256-bit URL-safe random token (state / nonce / PKCE verifier). */
    private String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return B64URL.encodeToString(bytes);
    }

    /** PKCE S256 challenge = base64url(SHA-256(verifier)) — RFC 7636 §4.2. */
    private String codeChallenge(String codeVerifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return B64URL.encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e); // cannot happen on a conformant JVM
        }
    }
}
