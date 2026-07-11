package com.example.sso.bootstrap.internal;

import com.example.sso.saml.relyingparty.SamlRelyingPartyAdminService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Seeds local federation test fixtures so the dockerized test clients in {@code docker-compose.test.yml}
 * work out of the box: an OIDC relying party for <b>oauth2-proxy</b> and a SAML relying party for a
 * <b>SimpleSAMLphp</b> service provider. Gated by {@code sso.integration-test.enabled} (on in dev, off in
 * prod). All endpoints are configurable so they can be adjusted to match the running test containers
 * without recompiling. Idempotent. (SCIM needs no fixture — it is exercised by a bearer-authenticated
 * client; see {@code scripts/scim_provision_flow.py}.)
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class IntegrationTestSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IntegrationTestSeeder.class);

    private final RegisteredClientRepository clients;
    private final PasswordEncoder passwordEncoder;
    private final SamlRelyingPartyAdminService relyingParties;
    private final boolean enabled;
    private final String oidcClientId;
    private final String oidcClientSecret;
    private final String oidcRedirectUri;
    private final String samlSpEntityId;
    private final String samlSpAcsUrl;

    public IntegrationTestSeeder(
            RegisteredClientRepository clients, PasswordEncoder passwordEncoder,
            SamlRelyingPartyAdminService relyingParties,
            @Value("${sso.integration-test.enabled:false}") boolean enabled,
            @Value("${sso.integration-test.oidc-client-id:oauth2-proxy}") String oidcClientId,
            @Value("${sso.integration-test.oidc-client-secret:oauth2-proxy-secret}") String oidcClientSecret,
            @Value("${sso.integration-test.oidc-redirect-uri:http://localhost:4180/oauth2/callback}") String oidcRedirectUri,
            @Value("${sso.integration-test.saml-sp-entity-id:urn:test:simplesamlphp}") String samlSpEntityId,
            @Value("${sso.integration-test.saml-sp-acs-url:http://localhost:8088/simplesaml/module.php/saml/sp/saml2-acs.php/default-sp}") String samlSpAcsUrl) {
        this.clients = clients;
        this.passwordEncoder = passwordEncoder;
        this.relyingParties = relyingParties;
        this.enabled = enabled;
        this.oidcClientId = oidcClientId;
        this.oidcClientSecret = oidcClientSecret;
        this.oidcRedirectUri = oidcRedirectUri;
        this.samlSpEntityId = samlSpEntityId;
        this.samlSpAcsUrl = samlSpAcsUrl;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }

        seedOauth2ProxyClient();
        seedSimpleSamlPhpSp();
    }

    private void seedOauth2ProxyClient() {
        if (clients.findByClientId(oidcClientId) != null) {
            return;
        }

        clients.save(RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(oidcClientId)
                .clientSecret(passwordEncoder.encode(oidcClientSecret))
                .clientName("oauth2-proxy (OIDC test RP)")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(oidcRedirectUri)
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .scope(OidcScopes.EMAIL)
                .clientSettings(ClientSettings.builder()
                        .requireAuthorizationConsent(false) // first-party test RP: skip the consent screen
                        .requireProofKey(false)             // confidential client (uses client_secret)
                        .build())
                .build());
        log.info("[integration-test] seeded OIDC test client '{}' (redirect {})", oidcClientId, oidcRedirectUri);
    }

    private void seedSimpleSamlPhpSp() {
        // Idempotent; defaults allow IdP-initiated SSO and do NOT require signed AuthnRequests, so a
        // vanilla SimpleSAMLphp SP works with no extra signing setup.
        relyingParties.ensureRelyingParty(samlSpEntityId, samlSpAcsUrl);
        log.info("[integration-test] ensured SAML relying party '{}' (ACS {})", samlSpEntityId, samlSpAcsUrl);
    }
}
