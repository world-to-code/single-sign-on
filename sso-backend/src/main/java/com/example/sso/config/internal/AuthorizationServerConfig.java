package com.example.sso.config.internal;

import com.example.sso.audit.AuditService;
import com.example.sso.authpolicy.factor.Factors;
import com.example.sso.crypto.ClientSecretHasher;
import com.example.sso.crypto.RsaKeyService;
import com.example.sso.oidc.BackChannelLogout;
import com.example.sso.oidc.ConsentPage;
import com.example.sso.oidc.OidcBackchannelSessionIndex;
import com.example.sso.portal.access.AppAssignmentFilter;
import com.example.sso.portal.stepup.AppStepUpFilter;
import com.example.sso.portal.application.ApplicationService;
import com.example.sso.security.OrgContextFilter;
import com.example.sso.security.PolicyIpAccessFilter;
import com.example.sso.security.HostOrgResolver;
import com.example.sso.security.TenantHostFilter;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.session.policy.UserSessionPolicy;
import com.example.sso.user.account.UserService;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authorization.AuthenticatedAuthorizationManager;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.authentication.ClientSecretAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.context.SecurityContextHolderFilter;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * OIDC Provider — the OAuth2 Authorization Server (part of Spring Security 7).
 *
 * <p>Runs on its own high-priority filter chain matching the protocol endpoints
 * (/oauth2/*, /.well-known/*, /userinfo, …). Authentication of the resource owner is
 * delegated to the app login chain, so OIDC sign-in is MFA-gated: an unauthenticated user
 * is sent to /login, and one missing the TOTP factor is smart-redirected to /challenge/totp.
 *
 * <p>Client, authorization and consent state are persisted via the JDBC services
 * (schema in V3); tokens are signed with rotatable RSA keys ({@link RsaKeyService}).
 */
@Configuration
public class AuthorizationServerConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain authorizationServerSecurityFilterChain(
            HttpSecurity http, JWKSource<SecurityContext> jwkSource,
            RegisteredClientRepository registeredClients, UserService users, ApplicationService applications,
            UserSessionPolicy userSessionPolicy, AuditService audit,
            OrgContext orgContext, HostOrgResolver hostOrgResolver,
            ClientSecretHasher clientSecretHasher, PasswordEncoder passwordEncoder)
            throws Exception {

        OAuth2AuthorizationServerConfigurer authorizationServer = new OAuth2AuthorizationServerConfigurer();

        // Resource-owner login at /oauth2/authorize must have completed the user's auth policy.
        AuthorizationManager<RequestAuthorizationContext> mfaComplete =
                AuthorityAuthorizationManager.hasAuthority(Factors.MFA_COMPLETE);
        // Machine endpoints (token, etc.) authenticate the *client*, not a user — plain auth only.
        AuthorizationManager<RequestAuthorizationContext> plainAuthenticated =
                AuthenticatedAuthorizationManager.authenticated();

        http
                .securityMatcher(authorizationServer.getEndpointsMatcher())
                // Enable OIDC and advertise back-channel logout support in the discovery metadata (the
                // end_session endpoint is already enabled by the OIDC defaults).
                .with(authorizationServer, as -> as
                        // Client secrets are verified by their own encoder: server-generated secrets carry a fast
                        // keyed hash, and confining it here keeps it away from user-password verification.
                        .clientAuthentication(clientAuthentication -> clientAuthentication
                                .authenticationProviders(clientSecretEncoder(clientSecretHasher, passwordEncoder)))
                        // Replace the framework whitelabel consent screen with our branded, server-rendered
                        // page (same SPA visual identity); the endpoint still owns the scope/consent contract.
                        // The custom redirect_uri validator lets the first-party admin console be entered from
                        // any tenant subdomain (same-origin callback) without pre-registering each one.
                        .authorizationEndpoint(endpoint -> endpoint
                                .consentPage(ConsentPage.URI)
                                .authenticationProviders(adminConsoleRedirectValidator()))
                        .oidc(oidc -> oidc
                                .providerConfigurationEndpoint(providerConfig -> providerConfig
                                        .providerConfigurationCustomizer(metadata -> metadata
                                                .claim(BackChannelLogout.METADATA_SUPPORTED, true)
                                                .claim(BackChannelLogout.METADATA_SESSION_SUPPORTED, true)))))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/oauth2/authorize").access(mfaComplete)
                        .anyRequest().access(plainAuthenticated))
                .csrf(csrf -> csrf.ignoringRequestMatchers(authorizationServer.getEndpointsMatcher()))
                .exceptionHandling(ex -> ex
                        // Unauthenticated browser at /authorize -> the React login SPA, which
                        // completes the auth policy and then resumes the saved request.
                        .defaultAuthenticationEntryPointFor(
                                new LoginUrlAuthenticationEntryPoint("/login"),
                                new MediaTypeRequestMatcher(MediaType.TEXT_HTML)))
                // The OIDC UserInfo endpoint is a resource-server endpoint (bearer access token).
                .oauth2ResourceServer(rs -> rs.jwt(Customizer.withDefaults()))
                // Per-tenant issuer: bind the org from the request HOST (subdomain) FIRST, so the host-derived
                // OIDC issuer is backed by that tenant's signing key and its own JWKS/discovery — even for the
                // unauthenticated discovery/JWKS endpoints. An unknown subdomain is refused (404). A bare host
                // passes through to the session-based OrgContextFilter below.
                .addFilterAfter(new TenantHostFilter(hostOrgResolver, orgContext),
                        SecurityContextHolderFilter.class)
                // Then bind the tenant context from the logged-in session (bare host), so the IP filter's
                // policy resolution sees the user's ORG-scoped session policy (not just global rules).
                .addFilterAfter(new OrgContextFilter(orgContext), TenantHostFilter.class)
                // Per-policy network (IP) access on the OIDC chain too: a blocked network must not be able to
                // complete SSO (/oauth2/authorize) even though it reached login. Anchored after the org
                // context filter so the resolved user's tenant policy (and its IP rules) is available.
                .addFilterAfter(new PolicyIpAccessFilter(userSessionPolicy, audit), OrgContextFilter.class)
                // Per-app step-up: redirect to /stepup when the client requires extra factors. Anchored
                // after the context filter (a registered-order filter) so it runs once the session is loaded.
                .addFilterAfter(new AppStepUpFilter(registeredClients, users, applications, audit),
                        SecurityContextHolderFilter.class)
                // Model B console entry: deny an UNASSIGNED user before step-up (no point acquiring
                // factors for an app they cannot enter). MUST live in THIS chain — the authorize endpoint
                // filter commits the response and never reaches the outer servlet chain.
                .addFilterBefore(new AppAssignmentFilter(registeredClients, applications, users, audit),
                        AppStepUpFilter.class);
        return http.build();
    }

    @Bean
    RegisteredClientRepository registeredClientRepository(JdbcTemplate jdbcTemplate, OrgContext orgContext,
            PlatformTransactionManager txManager) {
        // Bind clients to their owning tenant so a client can only be used under its own tenant's host —
        // the per-tenant issuer/key is otherwise selected purely by the (attacker-influenceable) host.
        return new OrgScopedRegisteredClientRepository(
                new JdbcRegisteredClientRepository(jdbcTemplate), orgContext, jdbcTemplate, txManager);
    }

    @Bean
    OAuth2AuthorizationService authorizationService(JdbcTemplate jdbcTemplate,
                                                    RegisteredClientRepository registeredClientRepository) {
        return new JdbcOAuth2AuthorizationService(jdbcTemplate, registeredClientRepository);
    }

    @Bean
    OAuth2AuthorizationConsentService authorizationConsentService(JdbcTemplate jdbcTemplate,
                                                                  RegisteredClientRepository registeredClientRepository) {
        return new JdbcOAuth2AuthorizationConsentService(jdbcTemplate, registeredClientRepository);
    }

    @Bean
    JWKSource<SecurityContext> jwkSource(RsaKeyService rsaKeyService) {
        return (jwkSelector, securityContext) -> jwkSelector.select(rsaKeyService.buildJwkSet());
    }

    /**
     * The signing encoder for every issued JWT (the authorization server picks this bean up; the
     * back-channel logout_token signs through it too). The JWKS keeps rotated-away keys published for
     * verification overlap across rotation, so a signing selection matches SEVERAL keys — without a
     * configured selector {@code NimbusJwtEncoder} refuses. {@code buildJwkSet} orders the ACTIVE key
     * first, so signing selects the first match.
     */
    @Bean
    JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource) {
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(jwkSource);
        encoder.setJwkSelector(List::getFirst);
        return encoder;
    }

    @Bean
    JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings() {
        // No fixed issuer: the issuer is derived per-request from the host, so each tenant subdomain
        // (acme.idp.example.com) is its own OIDC issuer with its own discovery document + JWKS, backed by
        // that tenant's signing key. The bare platform host derives back to the sso.issuer value.
        return AuthorizationServerSettings.builder().build();
    }

    /**
     * Adds profile/email claims to the ID token and the user's roles to the access token
     * (so the admin API resource server can authorize). Resolved from the domain user;
     * skipped for client-credentials tokens (no associated user).
     */
    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> oidcTokenCustomizer(UserService users,
            OidcBackchannelSessionIndex backchannelIndex) {
        return new OidcTokenClaimsCustomizer(users, backchannelIndex);
    }

    /**
     * Swaps the framework's default authorization-code request validator for one that additionally accepts the
     * first-party admin console's same-origin {@code /admin/callback} redirect at any tenant subdomain (see
     * {@link AdminConsoleRedirectUriValidator}); all other clients keep the strict registered-set check.
     */
    private Consumer<List<AuthenticationProvider>> adminConsoleRedirectValidator() {
        return providers -> providers.forEach(provider -> {
            if (provider instanceof OAuth2AuthorizationCodeRequestAuthenticationProvider codeRequestProvider) {
                codeRequestProvider.setAuthenticationValidator(new AdminConsoleRedirectUriValidator());
            }
        });
    }

    /** Points client-secret authentication at {@link ClientSecretPasswordEncoder}; see its Javadoc for why. */
    private Consumer<List<AuthenticationProvider>> clientSecretEncoder(ClientSecretHasher hasher,
            PasswordEncoder passwords) {
        ClientSecretPasswordEncoder encoder = new ClientSecretPasswordEncoder(hasher, passwords);
        return providers -> providers.forEach(provider -> {
            if (provider instanceof ClientSecretAuthenticationProvider secretProvider) {
                secretProvider.setPasswordEncoder(encoder);
            }
        });
    }

}
