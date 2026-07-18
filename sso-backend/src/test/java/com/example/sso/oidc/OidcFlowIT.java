package com.example.sso.oidc;

import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.organization.OrganizationView;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OIDC token-endpoint verification via MockMvc (POST endpoints work reliably under
 * MockMvc). The full browser authorization-code + ID-token flow is verified against the
 * live server by scripts/oidc-authcode-flow.sh (MockMvc mis-parses the GET /authorize
 * query string, so it cannot exercise that endpoint).
 */
@AutoConfigureMockMvc
class OidcFlowIT extends AbstractIntegrationTest {

    private static final String CLIENT_ID = "test-client";
    private static final String CLIENT_SECRET = "test-secret";

    private static final String TENANT_CLIENT_ID = "tenant-client";

    @Autowired
    MockMvc mvc;
    @Autowired
    RegisteredClientRepository clients;
    @Autowired
    PasswordEncoder passwordEncoder;
    @Autowired
    OrgContext orgContext;
    @Autowired
    OrganizationService organizations;

    @BeforeEach
    void ensureClient() {
        saveClient(CLIENT_ID, null); // the global/platform client (usable only under the bare host)
    }

    // Create a client owned by the given org (null = global), created within that org's context so the
    // OrgScopedRegisteredClientRepository stamps it correctly.
    private void saveClient(String clientId, UUID org) {
        Runnable create = () -> {
            if (clients.findByClientId(clientId) == null) {
                clients.save(RegisteredClient.withId(UUID.randomUUID().toString())
                        .clientId(clientId)
                        .clientSecret(passwordEncoder.encode(CLIENT_SECRET))
                        .clientName("Test Client " + clientId)
                        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                        .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                        .redirectUri("http://127.0.0.1:8080/callback")
                        .scope(OidcScopes.OPENID)
                        .scope(OidcScopes.PROFILE)
                        .clientSettings(ClientSettings.builder()
                                .requireAuthorizationConsent(false)
                                .requireProofKey(false)
                                .build())
                        .build());
            }
        };
        if (org == null) {
            create.run();
        } else {
            orgContext.runInOrg(org, create);
        }
    }

    private UUID defaultOrgId() {
        return organizations.findBySlug(DEFAULT_ORG_SLUG).orElseThrow().getId();
    }

    @Test
    void clientCredentialsIssuesRsaSignedJwt() throws Exception {
        // The issuer is now derived from the request host; the bare platform host derives back to sso.issuer.
        String response = mvc.perform(post("http://localhost:9000/oauth2/token")
                        .param("grant_type", "client_credentials")
                        .param("scope", "profile")
                        .with(httpBasic(CLIENT_ID, CLIENT_SECRET)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).contains("access_token").contains("\"token_type\":\"Bearer\"");

        SignedJWT jwt = SignedJWT.parse(extractJson(response, "access_token"));
        assertThat(jwt.getHeader().getAlgorithm().getName()).isEqualTo("RS256");
        assertThat(jwt.getHeader().getKeyID()).isNotBlank();
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("http://localhost:9000");
        assertThat(jwt.getJWTClaimsSet().getAudience()).contains(CLIENT_ID);
    }

    @Test
    void aGlobalClientCannotMintAtATenantHost() throws Exception {
        // The global client belongs to the platform, not to a tenant — presenting its credentials at a
        // tenant's host (where its key/issuer would be used) must be rejected, not mint a cross-tenant token.
        mvc.perform(post("http://" + DEFAULT_ORG_SLUG + ".localhost:9000/oauth2/token")
                        .param("grant_type", "client_credentials")
                        .with(httpBasic(CLIENT_ID, CLIENT_SECRET)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aTenantOwnedClientMintsUnderItsOwnIssuer() throws Exception {
        // Per-tenant issuer + client: a client OWNED by the tenant, used at the tenant's host, mints a token
        // whose issuer is that tenant's host.
        saveClient(TENANT_CLIENT_ID, defaultOrgId());

        String response = mvc.perform(post("http://" + DEFAULT_ORG_SLUG + ".localhost:9000/oauth2/token")
                        .param("grant_type", "client_credentials")
                        .param("scope", "profile")
                        .with(httpBasic(TENANT_CLIENT_ID, CLIENT_SECRET)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        SignedJWT jwt = SignedJWT.parse(extractJson(response, "access_token"));
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("http://" + DEFAULT_ORG_SLUG + ".localhost:9000");
        assertThat(jwt.getHeader().getKeyID()).isNotBlank();
    }

    @Test
    void twoTenantsSharingAClientIdEachAuthenticateAndMintUnderTheirOwnIssuer() throws Exception {
        // Per-tenant client_id: org A (the seeded default) and a fresh org B each own a client with the SAME
        // client_id. The token endpoint must resolve the tier-correct client per host — never the other tenant's
        // — so each mints under its OWN issuer. (If findByClientId returned an arbitrary shared-client_id row,
        // one tenant's client would fail to authenticate at its own host.)
        String sharedClientId = "shared-" + UUID.randomUUID().toString().substring(0, 8);
        OrganizationView orgB = organizations.create(
                new NewOrganization("shared-b-" + UUID.randomUUID().toString().substring(0, 8), "Org B"));
        saveClient(sharedClientId, defaultOrgId());
        saveClient(sharedClientId, orgB.id());

        String atA = mvc.perform(post("http://" + DEFAULT_ORG_SLUG + ".localhost:9000/oauth2/token")
                        .param("grant_type", "client_credentials").param("scope", "profile")
                        .with(httpBasic(sharedClientId, CLIENT_SECRET)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(SignedJWT.parse(extractJson(atA, "access_token")).getJWTClaimsSet().getIssuer())
                .isEqualTo("http://" + DEFAULT_ORG_SLUG + ".localhost:9000");

        String atB = mvc.perform(post("http://" + orgB.slug() + ".localhost:9000/oauth2/token")
                        .param("grant_type", "client_credentials").param("scope", "profile")
                        .with(httpBasic(sharedClientId, CLIENT_SECRET)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(SignedJWT.parse(extractJson(atB, "access_token")).getJWTClaimsSet().getIssuer())
                .isEqualTo("http://" + orgB.slug() + ".localhost:9000");
    }

    @Test
    void aTenantOwnedClientCannotMintAtTheBareHost() throws Exception {
        // The converse: a tenant's client is not usable on the platform host either — strict per-tier binding.
        saveClient(TENANT_CLIENT_ID, defaultOrgId());

        mvc.perform(post("http://localhost:9000/oauth2/token")
                        .param("grant_type", "client_credentials")
                        .with(httpBasic(TENANT_CLIENT_ID, CLIENT_SECRET)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anUnknownTenantSubdomainIsRefused() throws Exception {
        // The host derives the issuer, so an unrecognised subdomain must not mint one — 404, not a token.
        mvc.perform(post("http://no-such-tenant.localhost:9000/oauth2/token")
                        .param("grant_type", "client_credentials")
                        .with(httpBasic(CLIENT_ID, CLIENT_SECRET)))
                .andExpect(status().isNotFound());
    }

    @Test
    void anArbitraryHostIsRefused() throws Exception {
        // A Host that is neither a base domain nor a tenant subdomain must not derive its own issuer
        // (host-header issuer forgery / discovery poisoning) — refuse it before any token is minted.
        mvc.perform(post("http://evil.com/oauth2/token")
                        .param("grant_type", "client_credentials")
                        .with(httpBasic(CLIENT_ID, CLIENT_SECRET)))
                .andExpect(status().isNotFound());
    }

    @Test
    void tokenEndpointRejectsBadClientSecret() throws Exception {
        mvc.perform(post("/oauth2/token")
                        .param("grant_type", "client_credentials")
                        .with(httpBasic(CLIENT_ID, "wrong-secret")))
                .andExpect(status().isUnauthorized());
    }

    private static String extractJson(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker) + marker.length();
        int end = json.indexOf("\"", start);
        return json.substring(start, end);
    }
}
