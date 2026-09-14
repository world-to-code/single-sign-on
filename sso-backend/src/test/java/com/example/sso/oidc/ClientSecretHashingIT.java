package com.example.sso.oidc;

import com.example.sso.crypto.ClientSecretHasher;
import com.example.sso.support.AbstractIntegrationTest;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The token endpoint against a real context: a client whose secret was stored as a keyed hash authenticates,
 * a wrong secret does not, and the fast hash is confined to client authentication — the application's password
 * encoder, which verifies user passwords, never accepts it.
 */
@AutoConfigureMockMvc
class ClientSecretHashingIT extends AbstractIntegrationTest {

    private static final String SECRET = "Zk3vQ0x9mE7rT2yU5iO8pA1sD4fG6hJ9kL2zX5cV8bN";

    @Autowired
    MockMvc mvc;
    @Autowired
    RegisteredClientRepository clients;
    @Autowired
    ClientSecretHasher hasher;
    @Autowired
    PasswordEncoder passwordEncoder;

    @Test
    void aClientWithAKeyedHashSecretObtainsAToken() throws Exception {
        String secret = SECRET + UUID.randomUUID();
        String clientId = saveHashedClient(secret);

        mvc.perform(post("http://localhost:9000/oauth2/token")
                        .param("grant_type", "client_credentials")
                        .param("scope", OidcScopes.PROFILE)
                        .with(httpBasic(clientId, secret)))
                .andExpect(status().isOk());
    }

    @Test
    void aWrongSecretIsRefused() throws Exception {
        String secret = SECRET + UUID.randomUUID();
        String clientId = saveHashedClient(secret);

        mvc.perform(post("http://localhost:9000/oauth2/token")
                        .param("grant_type", "client_credentials")
                        .with(httpBasic(clientId, secret + "x")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theUserPasswordEncoderNeverAcceptsTheKeyedHash() {
        String encoded = hasher.encode(SECRET);
        AtomicBoolean accepted = new AtomicBoolean();

        Throwable thrown = catchThrowable(() -> accepted.set(passwordEncoder.matches(SECRET, encoded)));

        // Either outcome is a refusal: the encoder has no mapping for the id, or it answers no.
        assertThat(accepted).isFalse();
        if (thrown != null) {
            assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
        }
    }

    // Each client gets its own secret: the repository refuses two clients sharing a stored secret, and an unsalted
    // keyed hash maps equal secrets to equal values. Server-generated 256-bit secrets never collide in practice.
    private String saveHashedClient(String secret) {
        String clientId = "hashed-" + UUID.randomUUID().toString().substring(0, 8);
        clients.save(RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(clientId)
                .clientSecret(hasher.encode(secret))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope(OidcScopes.PROFILE)
                .build());
        return clientId;
    }
}
