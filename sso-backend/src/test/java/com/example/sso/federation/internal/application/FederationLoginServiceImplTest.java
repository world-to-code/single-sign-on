package com.example.sso.federation.internal.application;

import com.example.sso.federation.FederatedIdentity;
import com.example.sso.federation.FederationAuthorization;
import com.example.sso.tenancy.OrgContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FederationLoginServiceImpl}: the authorization request carries the correct OAuth/OIDC
 * parameters and a valid PKCE S256 challenge derived from the returned verifier, and the callback threads the
 * validated claims (plus the provider's JIT policy) into a {@link FederatedIdentity}. Discovery/token/JWKS are
 * mocked — their HTTP + validation is covered by {@code OidcUpstreamClient} / {@code IdTokenVerifier}.
 */
@ExtendWith(MockitoExtension.class)
class FederationLoginServiceImplTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final String ALIAS = "google";
    private static final String ISSUER = "https://accounts.example.com";
    private static final String REDIRECT = "https://acme.example/api/auth/federation/google/callback";
    private static final OidcMetadata METADATA =
            new OidcMetadata(ISSUER, ISSUER + "/authorize", ISSUER + "/token", ISSUER + "/jwks");

    @Mock private FederationConfigStore configStore;
    @Mock private OrgContext orgContext;
    @Mock private OidcUpstreamClient upstream;
    @Mock private IdTokenVerifier verifier;

    private FederationLoginServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new FederationLoginServiceImpl(configStore, orgContext, upstream, verifier);
        when(orgContext.callInOrg(eq(ORG), any())).thenAnswer(i -> ((Supplier<?>) i.getArgument(1)).get());
        when(configStore.resolveEnabled(ORG, ALIAS)).thenReturn(
                new ResolvedProvider(ALIAS, ISSUER, "client-123", "s3cret", "openid email", true, false));
        when(upstream.discover(ISSUER)).thenReturn(METADATA);
    }

    @Test
    void beginLoginBuildsTheAuthorizationRequestWithAValidPkceChallenge() {
        FederationAuthorization authorization = service.beginLogin(ORG, ALIAS, REDIRECT);

        String uri = authorization.authorizationUri();
        assertThat(uri).startsWith(ISSUER + "/authorize?")
                .contains("response_type=code")
                .contains("client_id=client-123")
                .contains("code_challenge_method=S256")
                .contains("state=" + authorization.state())
                .contains("nonce=" + authorization.nonce());
        // scope "openid email" is URL-encoded (space → %20); the redirect is percent-encoded too.
        assertThat(uri).contains("scope=openid%20email");
        // ':' and '/' are legal query-component chars, so UriComponentsBuilder leaves the redirect_uri literal.
        assertThat(uri).contains("redirect_uri=" + REDIRECT);
        // PKCE binding: the challenge in the URI MUST be base64url(SHA-256(verifier)) of the returned verifier.
        assertThat(uri).contains("code_challenge=" + s256(authorization.codeVerifier()));
        assertThat(authorization.state()).isNotBlank();
        assertThat(authorization.nonce()).isNotBlank();
        assertThat(authorization.codeVerifier()).isNotBlank();
        // The provider read runs INSIDE the tenant's RLS context — pre-auth has none bound by the request filter.
        verify(orgContext).callInOrg(eq(ORG), any());
    }

    @Test
    void completeLoginReturnsTheVerifiedIdentityWithTheProvidersJitPolicy() {
        when(upstream.exchangeCodeForIdToken(METADATA, "client-123", "s3cret", "code-1", REDIRECT, "verifier-1"))
                .thenReturn("id-token");
        when(verifier.verify(METADATA, "client-123", "id-token", "nonce-1"))
                .thenReturn(new VerifiedIdToken("sub-9", "ada@example.com", true, "Ada"));

        FederatedIdentity identity = service.completeLogin(ORG, ALIAS, "code-1", REDIRECT, "nonce-1", "verifier-1");

        assertThat(identity.subject()).isEqualTo("sub-9");
        assertThat(identity.email()).isEqualTo("ada@example.com");
        assertThat(identity.emailVerified()).isTrue();
        assertThat(identity.jitProvisioningAllowed()).isTrue(); // carried from the resolved provider config
    }

    private String s256(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
