package com.example.sso.oidc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Seeds a demo confidential OIDC client (authorization_code + PKCE, refresh_token,
 * client_credentials) for LOCAL TESTING only. Gated by {@code sso.demo-client.enabled} so it is NOT
 * seeded in production (it carries a well-known secret). Idempotent; token lifetimes are configurable.
 */
@Component
public class OidcClientSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OidcClientSeeder.class);

    private final RegisteredClientRepository clients;
    private final PasswordEncoder passwordEncoder;
    private final boolean enabled;
    private final int accessTtlMinutes;
    private final int refreshTtlDays;

    public OidcClientSeeder(RegisteredClientRepository clients, PasswordEncoder passwordEncoder,
                            @Value("${sso.demo-client.enabled:true}") boolean enabled,
                            @Value("${sso.demo-client.access-token-ttl-minutes:30}") int accessTtlMinutes,
                            @Value("${sso.demo-client.refresh-token-ttl-days:7}") int refreshTtlDays) {
        this.clients = clients;
        this.passwordEncoder = passwordEncoder;
        this.enabled = enabled;
        this.accessTtlMinutes = accessTtlMinutes;
        this.refreshTtlDays = refreshTtlDays;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (enabled) {
            seedDemoClient();
        }
    }

    private void seedDemoClient() {
        if (clients.findByClientId("demo-client") != null) {
            return;
        }
        RegisteredClient demoClient = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("demo-client")
                .clientSecret(passwordEncoder.encode("demo-secret"))
                .clientName("Demo OIDC Client")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .redirectUri("http://127.0.0.1:8080/login/oauth2/code/demo")
                .redirectUri("https://oidcdebugger.com/debug")
                .postLogoutRedirectUri("http://127.0.0.1:8080/")
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .scope(OidcScopes.EMAIL)
                .clientSettings(ClientSettings.builder()
                        .requireAuthorizationConsent(true)
                        .requireProofKey(true)   // PKCE
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(accessTtlMinutes))
                        .refreshTokenTimeToLive(Duration.ofDays(refreshTtlDays))
                        .build())
                .build();
        clients.save(demoClient);
        log.info("Seeded demo OIDC client 'demo-client' (secret: demo-secret) — change before real use.");
    }
}
