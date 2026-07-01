package com.example.sso.oidc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Seeds the first-party "admin-console" public OIDC client used to enter the admin console via a real
 * authorization-code + PKCE flow against this IdP. Redirect URIs and token lifetimes come from
 * configuration ({@code sso.admin-console.*}).
 *
 * <p>The "additional authentication" on admin entry is enforced client-side by {@code AdminCallback}
 * (a fresh step-up re-auth through {@code /api/auth/reauth}, which ALWAYS prompts for a strong factor
 * and re-stamps the step-up clock). So no per-app step-up policy/assignment is attached here — that
 * (presence-based) gate would only prompt when a factor is absent and would double-prompt with the
 * reauth modal. Idempotent.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class AdminPortalSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminPortalSeeder.class);

    /** Client id of the first-party admin console (the elevation gate pins tokens to this client). */
    public static final String CLIENT_ID = "admin-console";

    /** Reserved privilege-elevation scope the admin elevation gate requires (only this client may hold it). */
    public static final String ADMIN_SCOPE = "admin";

    private final RegisteredClientRepository clients;
    private final List<String> redirectUris;
    private final int accessTtlMinutes;
    private final int refreshTtlMinutes;

    public AdminPortalSeeder(RegisteredClientRepository clients,
                             @Value("${sso.admin-console.redirect-uris}") List<String> redirectUris,
                             @Value("${sso.admin-console.access-token-ttl-minutes:5}") int accessTtlMinutes,
                             @Value("${sso.admin-console.refresh-token-ttl-minutes:30}") int refreshTtlMinutes) {
        this.clients = clients;
        this.redirectUris = redirectUris;
        this.accessTtlMinutes = accessTtlMinutes;
        this.refreshTtlMinutes = refreshTtlMinutes;
    }

    @Override
    public void run(ApplicationArguments args) {
        RegisteredClient existing = clients.findByClientId(CLIENT_ID);
        if (existing == null) {
            clients.save(buildAdminConsole(UUID.randomUUID().toString()));
            log.info("Seeded first-party OIDC client '{}' (public, PKCE) for the admin console.", CLIENT_ID);
            return;
        }

        // The client may pre-date the "admin" elevation scope (dev DB). Backfill it so the access token
        // can carry scope=admin without re-seeding.
        if (!existing.getScopes().contains(ADMIN_SCOPE)) {
            clients.save(RegisteredClient.from(existing).scope(ADMIN_SCOPE).build());
            log.info("Updated OIDC client '{}' to include the '{}' elevation scope.", CLIENT_ID, ADMIN_SCOPE);
        }
    }

    private RegisteredClient buildAdminConsole(String id) {
        RegisteredClient.Builder builder = RegisteredClient.withId(id)
                .clientId(CLIENT_ID)
                .clientName("Admin Console")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE) // public client (PKCE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .scope(ADMIN_SCOPE) // privilege-elevation scope required by the admin elevation gate
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)              // mandatory PKCE
                        .requireAuthorizationConsent(false) // first-party: no consent screen
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(accessTtlMinutes)) // short-lived admin proof
                        .refreshTokenTimeToLive(Duration.ofMinutes(refreshTtlMinutes))
                        .build());

        redirectUris.forEach(builder::redirectUri);
        return builder.build();
    }
}
