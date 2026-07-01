package com.example.sso.admin;

import com.example.sso.oidc.AdminPortalSeeder;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jose.jws.JwsAlgorithm;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * OAuth2/OIDC client (RegisteredClient) administration: registration with full Authorization
 * Server settings, listing, and deletion. These are AS-side concerns, isolated from user admin.
 */
@Service
@RequiredArgsConstructor
public class ClientAdminService {

    private final RegisteredClientRepository registeredClients;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;

    @Transactional(readOnly = true)
    public List<ClientView> listClients() {
        return jdbcTemplate.query(
                "SELECT id, client_id, client_name, scopes, authorization_grant_types, redirect_uris, "
                        + "initiate_login_uri FROM oauth2_registered_client",
                (rs, rowNum) -> new ClientView(rs.getString("id"), rs.getString("client_id"),
                        rs.getString("client_name"), rs.getString("scopes"),
                        rs.getString("authorization_grant_types"), rs.getString("redirect_uris"),
                        rs.getString("initiate_login_uri")));
    }

    /** Registers a new OAuth2/OIDC client with full AS settings. Returns the secret once (confidential). */
    @Transactional
    public ClientCreated createClient(CreateClientRequest request) {
        if (registeredClients.findByClientId(request.clientId()) != null) {
            throw new ConflictException("clientId already exists");
        }

        RegisteredClient.Builder builder = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(request.clientId())
                .clientName((request.clientName() == null || request.clientName().isBlank())
                        ? request.clientId() : request.clientName());

        Set<String> authMethods = (request.clientAuthenticationMethods() == null
                || request.clientAuthenticationMethods().isEmpty())
                ? Set.of("client_secret_basic", "client_secret_post") : request.clientAuthenticationMethods();
        String secret = null;
        if (request.publicClient()) {
            builder.clientAuthenticationMethod(ClientAuthenticationMethod.NONE);
        } else {
            authMethods.forEach(m -> builder.clientAuthenticationMethod(new ClientAuthenticationMethod(m)));
            boolean needsSecret = authMethods.stream().anyMatch(m -> m.startsWith("client_secret"));
            if (needsSecret) {
                secret = generateSecret();
                builder.clientSecret(passwordEncoder.encode(secret));
                if (request.clientSecretDays() != null) {
                    builder.clientSecretExpiresAt(Instant.now().plus(request.clientSecretDays(), ChronoUnit.DAYS));
                }
            }
        }

        Set<String> grantTypes = (request.grantTypes() == null || request.grantTypes().isEmpty())
                ? Set.of("authorization_code", "refresh_token") : request.grantTypes();
        grantTypes.forEach(g -> builder.authorizationGrantType(new AuthorizationGrantType(g)));

        if (request.redirectUris() != null) {
            request.redirectUris().forEach(builder::redirectUri);
        }
        if (request.postLogoutRedirectUris() != null) {
            request.postLogoutRedirectUris().forEach(builder::postLogoutRedirectUri);
        }
        Set<String> scopes = (request.scopes() == null || request.scopes().isEmpty())
                ? Set.of("openid", "profile", "email") : request.scopes();
        // "admin" is the reserved privilege-elevation scope for the first-party admin-console client only;
        // refuse it on any admin-created client so an admin-scoped token can't be minted elsewhere.
        if (scopes.contains(AdminPortalSeeder.ADMIN_SCOPE)) {
            throw new BadRequestException("the 'admin' scope is reserved and cannot be assigned to a client");
        }
        scopes.forEach(builder::scope);

        builder.clientSettings(clientSettings(request)).tokenSettings(tokenSettings(request));
        registeredClients.save(builder.build());
        // initiate_login_uri is our launch metadata (not a Spring RegisteredClient field); persist it
        // on the same row after Spring's save.
        if (StringUtils.hasText(request.initiateLoginUri())) {
            jdbcTemplate.update("UPDATE oauth2_registered_client SET initiate_login_uri = ? WHERE client_id = ?",
                    request.initiateLoginUri().trim(), request.clientId());
        }
        return new ClientCreated(request.clientId(), secret);
    }

    @Transactional
    public void deleteClient(String id) {
        String clientId = jdbcTemplate.query("SELECT client_id FROM oauth2_registered_client WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null, id);
        if (clientId == null) {
            throw new NotFoundException("Client not found");
        }
        // The first-party admin console is a fixed part of the platform (auto-assigned to admins,
        // launches /admin); it is protected from deletion so the admin entry point can't be removed.
        if (AdminPortalSeeder.CLIENT_ID.equals(clientId)) {
            throw new ConflictException("the admin console client is protected and cannot be deleted");
        }
        jdbcTemplate.update("DELETE FROM oauth2_registered_client WHERE id = ?", id);
    }

    private ClientSettings clientSettings(CreateClientRequest request) {
        ClientSettings.Builder settings = ClientSettings.builder()
                .requireAuthorizationConsent(request.requireConsent())
                .requireProofKey(request.publicClient() || request.requireProofKey());
        if (StringUtils.hasText(request.jwkSetUrl())) {
            settings.jwkSetUrl(request.jwkSetUrl());
        }
        if (StringUtils.hasText(request.tokenEndpointAuthSigningAlgorithm())) {
            settings.tokenEndpointAuthenticationSigningAlgorithm(jwsAlgorithm(request.tokenEndpointAuthSigningAlgorithm()));
        }
        if (StringUtils.hasText(request.x509SubjectDn())) {
            settings.x509CertificateSubjectDN(request.x509SubjectDn());
        }
        return settings.build();
    }

    private TokenSettings tokenSettings(CreateClientRequest request) {
        TokenSettings.Builder settings = TokenSettings.builder()
                .accessTokenTimeToLive(Duration.ofMinutes(orDefault(request.accessTokenMinutes(), 30)))
                .refreshTokenTimeToLive(Duration.ofDays(orDefault(request.refreshTokenDays(), 7)))
                .authorizationCodeTimeToLive(Duration.ofMinutes(orDefault(request.authorizationCodeMinutes(), 5)))
                .deviceCodeTimeToLive(Duration.ofMinutes(orDefault(request.deviceCodeMinutes(), 5)))
                .reuseRefreshTokens(request.reuseRefreshTokens())
                .x509CertificateBoundAccessTokens(request.x509BoundAccessTokens())
                .accessTokenFormat("REFERENCE".equalsIgnoreCase(request.accessTokenFormat())
                        ? OAuth2TokenFormat.REFERENCE : OAuth2TokenFormat.SELF_CONTAINED);
        SignatureAlgorithm idTokenAlg = SignatureAlgorithm.from(
                StringUtils.hasText(request.idTokenSignatureAlgorithm()) ? request.idTokenSignatureAlgorithm() : "RS256");
        settings.idTokenSignatureAlgorithm(idTokenAlg == null ? SignatureAlgorithm.RS256 : idTokenAlg);
        return settings.build();
    }

    private static JwsAlgorithm jwsAlgorithm(String name) {
        return name.toUpperCase().startsWith("HS") ? MacAlgorithm.from(name) : SignatureAlgorithm.from(name);
    }

    private static int orDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private String generateSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
