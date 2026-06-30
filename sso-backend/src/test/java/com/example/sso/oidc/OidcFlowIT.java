package com.example.sso.oidc;

import com.example.sso.support.AbstractIntegrationTest;
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

    @Autowired
    MockMvc mvc;
    @Autowired
    RegisteredClientRepository clients;
    @Autowired
    PasswordEncoder passwordEncoder;

    @BeforeEach
    void ensureClient() {
        if (clients.findByClientId(CLIENT_ID) == null) {
            clients.save(RegisteredClient.withId(UUID.randomUUID().toString())
                    .clientId(CLIENT_ID)
                    .clientSecret(passwordEncoder.encode(CLIENT_SECRET))
                    .clientName("Test Client")
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
    }

    @Test
    void clientCredentialsIssuesRsaSignedJwt() throws Exception {
        String response = mvc.perform(post("/oauth2/token")
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
