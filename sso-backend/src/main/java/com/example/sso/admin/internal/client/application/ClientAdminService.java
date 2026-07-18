package com.example.sso.admin.internal.client.application;

import static org.springframework.security.oauth2.core.AuthorizationGrantType.AUTHORIZATION_CODE;
import static org.springframework.security.oauth2.core.AuthorizationGrantType.REFRESH_TOKEN;
import static org.springframework.security.oauth2.core.ClientAuthenticationMethod.CLIENT_SECRET_BASIC;
import static org.springframework.security.oauth2.core.ClientAuthenticationMethod.CLIENT_SECRET_JWT;
import static org.springframework.security.oauth2.core.ClientAuthenticationMethod.CLIENT_SECRET_POST;
import static org.springframework.security.oauth2.core.ClientAuthenticationMethod.PRIVATE_KEY_JWT;
import static org.springframework.security.oauth2.core.oidc.OidcScopes.EMAIL;
import static org.springframework.security.oauth2.core.oidc.OidcScopes.OPENID;
import static org.springframework.security.oauth2.core.oidc.OidcScopes.PROFILE;
import static org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256;

import com.example.sso.admin.internal.client.domain.OAuth2RegisteredClientEntity;
import com.example.sso.admin.internal.client.domain.OAuth2RegisteredClientRepository;
import com.example.sso.tenancy.OrgTierGuard;
import com.example.sso.oidc.AdminPortalSeeder;
import com.example.sso.oidc.BackChannelLogout;
import com.example.sso.portal.application.ApplicationDeletedEvent;
import com.example.sso.shared.Page;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jose.jws.JwsAlgorithm;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * OAuth2/OIDC client (RegisteredClient) administration: registration with full Authorization Server settings, listing,
 * and deletion. These are AS-side concerns, isolated from user admin.
 */
@Service
@RequiredArgsConstructor
public class ClientAdminService {

    private static final Set<String> DEFAULT_AUTH_METHODS =
            Set.of(CLIENT_SECRET_BASIC.getValue(), CLIENT_SECRET_POST.getValue());
    // The methods the token endpoint can actually enforce at runtime. The framework also ships providers for
    // tls_client_auth / self_signed_tls_client_auth, but no mutual-TLS is terminated at the edge (no
    // server.ssl.client-auth / X509 converter), so a client saved with those could never authenticate —
    // reject them here instead of persisting a silently-unusable client. ('none' is a public client.)
    private static final Set<String> SUPPORTED_AUTH_METHODS = Set.of(
            CLIENT_SECRET_BASIC.getValue(), CLIENT_SECRET_POST.getValue(),
            CLIENT_SECRET_JWT.getValue(), PRIVATE_KEY_JWT.getValue());
    private static final Set<String> DEFAULT_GRANT_TYPES =
            Set.of(AUTHORIZATION_CODE.getValue(), REFRESH_TOKEN.getValue());
    private static final Set<String> DEFAULT_SCOPES = Set.of(OPENID, PROFILE, EMAIL);
    private static final int ACCESS_TOKEN_TTL_FALLBACK = 30;
    private static final int REFRESH_TOKEN_TTL_FALLBACK = 7;
    private static final int AUTHORIZATION_CODE_TTL_FALLBACK = 5;
    private static final int DEVICE_CODE_TTL_FALLBACK = 5;

    private final RegisteredClientRepository registeredClients;
    private final PasswordEncoder passwordEncoder;
    private final OAuth2RegisteredClientRepository clientRows;
    private final OrgTierGuard tierGuard;
    private final ApplicationEventPublisher events;

    @Transactional(readOnly = true)
    public List<ClientView> listClients() {
        // Scope to the acting tier: the platform admin (no org bound) sees global clients; a super-admin
        // drilled into an org (or a tenant admin) sees only that org's clients — never another tenant's.
        UUID org = tierGuard.currentTier();
        List<OAuth2RegisteredClientEntity> rows =
                org == null ? clientRows.findAllByOrgIdIsNull() : clientRows.findAllByOrgId(org);
        return rows.stream().map(ClientView::of).toList();
    }

    public Page<ClientView> listClients(int page, int size) {
        return Page.of(listClients(), page, size);
    }

    /** The first-party admin-console client (a GLOBAL, host-agnostic platform app), regardless of the acting
     *  tier — so it can be surfaced as a launchable app in EVERY tenant's portal, not just the platform host. */
    @Transactional(readOnly = true)
    public Optional<ClientView> firstPartyConsole() {
        return clientRows.findByClientId(AdminPortalSeeder.CLIENT_ID).map(ClientView::of);
    }

    /**
     * Registers a new OAuth2/OIDC client with full AS settings. Returns the generated secret once, when
     * one applies (a confidential client using client_secret auth); null for public / JWT / mTLS clients.
     */
    @Transactional
    public ClientCreated createClient(CreateClientRequest request) {
        if (registeredClients.findByClientId(request.clientId()) != null) {
            throw ConflictException.of("admin.client.duplicate");
        }

        String internalId = UUID.randomUUID().toString();
        RegisteredClient.Builder clientBuilder =
                RegisteredClient.withId(internalId)
                        .clientId(request.clientId())
                        .clientName(
                                StringUtils.hasText(request.clientName()) ? request.clientName() : request.clientId());

        Set<String> authMethods = CollectionUtils.isEmpty(request.clientAuthenticationMethods())
                ? DEFAULT_AUTH_METHODS : request.clientAuthenticationMethods();

        String secret = null;

        if (request.publicClient()) {
            clientBuilder.clientAuthenticationMethod(ClientAuthenticationMethod.NONE);
        } else {
            authMethods.forEach(m -> {
                if (!SUPPORTED_AUTH_METHODS.contains(m)) {
                    throw BadRequestException.of("admin.client.unsupportedAuthMethod", m, SUPPORTED_AUTH_METHODS);
                }
                clientBuilder.clientAuthenticationMethod(new ClientAuthenticationMethod(m));
            });
            boolean needsSecret = authMethods.stream()
                    .anyMatch(m -> m.startsWith("client_secret"));

            if (needsSecret) {
                secret = generateSecret();
                clientBuilder.clientSecret(passwordEncoder.encode(secret));
                if (request.clientSecretDays() != null) {
                    clientBuilder.clientSecretExpiresAt(
                            Instant.now()
                                    .plus(request.clientSecretDays(), ChronoUnit.DAYS));
                }
            }
        }

        Set<String> grantTypes =
                CollectionUtils.isEmpty(request.grantTypes())
                ? DEFAULT_GRANT_TYPES : request.grantTypes();
        grantTypes.forEach(g -> clientBuilder.authorizationGrantType(new AuthorizationGrantType(g)));

        if (request.redirectUris() != null) {
            request.redirectUris()
                    .forEach(clientBuilder::redirectUri);
        }
        if (request.postLogoutRedirectUris() != null) {
            request.postLogoutRedirectUris()
                    .forEach(clientBuilder::postLogoutRedirectUri);
        }
        Set<String> scopes = extractScopes(request);
        scopes.forEach(clientBuilder::scope);

        clientBuilder.clientSettings(clientSettings(request))
                .tokenSettings(tokenSettings(request));
        registeredClients.save(clientBuilder.build());

        // initiate_login_uri is our launch metadata (not a Spring RegisteredClient field); persist it on the
        // row we just saved, keyed by its internal id — client_id is unique only per tenant, so a client_id-keyed
        // update would overwrite every other tenant's (and the global) client sharing this client_id.
        if (StringUtils.hasText(request.initiateLoginUri())) {
            clientRows.updateInitiateLoginUriById(internalId, request.initiateLoginUri().trim());
        }

        return new ClientCreated(request.clientId(), secret);
    }

    @Transactional
    public void deleteClient(String id) {
        // Only a client in the actor's tier may be deleted — a super-admin drilled into org A cannot delete
        // another tenant's (or a global) client; mismatch → 404 (non-revealing).
        OAuth2RegisteredClientEntity client =
                tierGuard.requireInTier(clientRows.findById(id), () -> new NotFoundException("Client not found"));

        // The first-party admin console is a fixed part of the platform (auto-assigned to admins,
        // launches /admin); it is protected from deletion so the admin entry point can't be removed.
        if (AdminPortalSeeder.CLIENT_ID.equals(client.getClientId())) {
            throw ConflictException.of("admin.client.consoleProtected");
        }

        clientRows.deleteById(id);
        events.publishEvent(new ApplicationDeletedEvent(id));
    }

    private @NonNull Set<String> extractScopes(CreateClientRequest request) {
        Set<String> scopes =
                CollectionUtils.isEmpty(request.scopes()) ? DEFAULT_SCOPES : request.scopes();
        // "admin" is the reserved privilege-elevation scope for the first-party admin-console client only;
        // refuse it on any admin-created client so an admin-scoped token can't be minted elsewhere.
        if (scopes.contains(AdminPortalSeeder.ADMIN_SCOPE)) {
            throw BadRequestException.of("admin.client.adminScopeReserved");
        }
        return scopes;
    }

    private ClientSettings clientSettings(CreateClientRequest request) {
        ClientSettings.Builder settings = ClientSettings.builder()
                .requireAuthorizationConsent(request.requireConsent())
                .requireProofKey(request.publicClient() || request.requireProofKey());

        if (StringUtils.hasText(request.jwkSetUrl())) {
            settings.jwkSetUrl(request.jwkSetUrl());
        }

        if (StringUtils.hasText(request.tokenEndpointAuthSigningAlgorithm())) {
            settings.tokenEndpointAuthenticationSigningAlgorithm(
                    jwsAlgorithm(request.tokenEndpointAuthSigningAlgorithm()));
        }

        if (StringUtils.hasText(request.x509SubjectDn())) {
            settings.x509CertificateSubjectDN(request.x509SubjectDn());
        }

        // OIDC back-channel logout target, stored as custom ClientSettings (round-trips through the
        // client_settings JSON — no schema change). Read back by the logout-token sender on termination.
        if (StringUtils.hasText(request.backchannelLogoutUri())) {
            settings.setting(BackChannelLogout.CLIENT_SETTING_URI, request.backchannelLogoutUri().trim());
            settings.setting(BackChannelLogout.CLIENT_SETTING_SESSION_REQUIRED, request.backchannelLogoutSessionRequired());
        }

        return settings.build();
    }

    private TokenSettings tokenSettings(CreateClientRequest request) {
        Duration accessTokenTimeToLive = Duration.ofMinutes(
                orDefault(request.accessTokenMinutes(), ACCESS_TOKEN_TTL_FALLBACK));
        Duration refreshTokenTimeToLive = Duration.ofDays(
                orDefault(request.refreshTokenDays(), REFRESH_TOKEN_TTL_FALLBACK));
        Duration authorizationCodeTimeToLive = Duration.ofMinutes(
                orDefault(request.authorizationCodeMinutes(), AUTHORIZATION_CODE_TTL_FALLBACK));
        Duration deviceCodeTimeToLive = Duration.ofMinutes(
                orDefault(request.deviceCodeMinutes(), DEVICE_CODE_TTL_FALLBACK));

        TokenSettings.Builder settings = TokenSettings.builder()
                .accessTokenTimeToLive(accessTokenTimeToLive)
                .refreshTokenTimeToLive(refreshTokenTimeToLive)
                .authorizationCodeTimeToLive(authorizationCodeTimeToLive)
                .deviceCodeTimeToLive(deviceCodeTimeToLive)
                .reuseRefreshTokens(request.reuseRefreshTokens())
                .x509CertificateBoundAccessTokens(request.x509BoundAccessTokens())
                .accessTokenFormat(OAuth2TokenFormat.REFERENCE.getValue()
                        .equalsIgnoreCase(request.accessTokenFormat())
                        ? OAuth2TokenFormat.REFERENCE : OAuth2TokenFormat.SELF_CONTAINED);

        SignatureAlgorithm idTokenAlg = SignatureAlgorithm.from(StringUtils.hasText(request.idTokenSignatureAlgorithm())
                ? request.idTokenSignatureAlgorithm() : RS256.getName());
        settings.idTokenSignatureAlgorithm(idTokenAlg == null ? RS256 : idTokenAlg);

        return settings.build();
    }

    private JwsAlgorithm jwsAlgorithm(String name) {
        return name.toUpperCase()
                .startsWith("HS") ? MacAlgorithm.from(name) : SignatureAlgorithm.from(name);
    }

    private int orDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private String generateSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(bytes);
    }
}
