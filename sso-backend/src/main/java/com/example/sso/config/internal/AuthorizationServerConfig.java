package com.example.sso.config.internal;

import com.example.sso.audit.AuditService;
import com.example.sso.authpolicy.Factors;
import com.example.sso.crypto.RsaKeyService;
import com.example.sso.oidc.BackChannelLogout;
import com.example.sso.oidc.OidcBackchannelSessionIndex;
import com.example.sso.portal.AppAssignmentFilter;
import com.example.sso.portal.AppStepUpFilter;
import com.example.sso.portal.ApplicationService;
import com.example.sso.security.PolicyIpAccessFilter;
import com.example.sso.session.NetworkZoneService;
import com.example.sso.session.SessionPolicyService;
import com.example.sso.user.RoleRef;
import com.example.sso.user.UserService;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authorization.AuthenticatedAuthorizationManager;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.core.GrantedAuthority;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
            SessionPolicyService policyService, NetworkZoneService networkZones, AuditService audit)
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
                .with(authorizationServer, as -> as.oidc(oidc -> oidc
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
                // Per-policy network (IP) access on the OIDC chain too: a blocked network must not be able to
                // complete SSO (/oauth2/authorize) even though it reached login. Anchored after the context
                // filter so the resolved user (and policy) is available.
                .addFilterAfter(new PolicyIpAccessFilter(policyService, networkZones, audit), SecurityContextHolderFilter.class)
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
    RegisteredClientRepository registeredClientRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcRegisteredClientRepository(jdbcTemplate);
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

    @Bean
    JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings(@Value("${sso.issuer}") String issuer) {
        return AuthorizationServerSettings.builder()
                .issuer(issuer)
                .build();
    }

    /**
     * Adds profile/email claims to the ID token and the user's roles to the access token
     * (so the admin API resource server can authorize). Resolved from the domain user;
     * skipped for client-credentials tokens (no associated user).
     */
    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> oidcTokenCustomizer(UserService users,
            OidcBackchannelSessionIndex backchannelIndex) {
        return context -> {
            String username = context.getPrincipal().getName();
            users.findByUsername(username).ifPresent(user -> {
                boolean idToken = OidcParameterNames.ID_TOKEN.equals(context.getTokenType().getValue());
                boolean accessToken = OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType());

                if (idToken && context.getAuthorizedScopes().contains(OidcScopes.PROFILE)) {
                    context.getClaims().claim("name",
                            user.getDisplayName() != null ? user.getDisplayName() : username);
                    context.getClaims().claim("preferred_username", username);
                }

                if (idToken && context.getAuthorizedScopes().contains(OidcScopes.EMAIL)) {
                    context.getClaims().claim("email", user.getEmail());
                }

                // Authentication-context claims so RPs (ID token) AND the admin elevation gate (access
                // token) can verify HOW (and how strongly) the user authenticated — RFC 8176 amr, an acr
                // level, and the OIDC auth_time. Emitted on both the ID token and the bearer access token:
                // the admin API requires acr=mfa + a fresh auth_time on the access token (RFC 9470 step-up).
                if (idToken || accessToken) {
                    Set<String> auth = context.getPrincipal().getAuthorities().stream()
                            .map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
                    long factorCount = auth.stream().filter(a -> a.startsWith("FACTOR_")).count();
                    List<String> amr = amrValues(auth, factorCount);
                    if (!amr.isEmpty()) {
                        context.getClaims().claim("amr", amr);
                        context.getClaims().claim("acr", factorCount >= 2 ? "mfa" : "sfa");
                    }
                    // Emit time claims as String (epoch seconds), NOT Long/Instant: the JDBC authorization
                    // store re-serializes token claims through a locked-down Jackson mapper that REJECTS a
                    // bare java.lang.Long (PolymorphicTypeValidator) AND cannot reflectively serialize a
                    // java.time.Instant (JPMS InaccessibleObjectException) — both surface as a token-endpoint
                    // 500 on read-back. A String round-trips cleanly (like acr/azp). The marker already holds
                    // the epoch-seconds string, so we emit it verbatim; the admin gate parses it back.
                    auth.stream().filter(a -> a.startsWith(Factors.AUTH_TIME_PREFIX)).findFirst()
                            .ifPresent(a -> context.getClaims().claim("auth_time",
                                    a.substring(Factors.AUTH_TIME_PREFIX.length())));
                    // stepup_time: present only after a DELIBERATE /reauth step-up (not a plain login),
                    // so the admin elevation gate can require a recent re-authentication.
                    auth.stream().filter(a -> a.startsWith(Factors.STEPUP_TIME_PREFIX)).findFirst()
                            .ifPresent(a -> context.getClaims().claim("stepup_time",
                                    a.substring(Factors.STEPUP_TIME_PREFIX.length())));
                    // `org`: the organization (tenant) id this session logged into (tenant-first entry), so a
                    // relying party can scope the user to the tenant. String marker -> round-trips like acr.
                    auth.stream().filter(a -> a.startsWith(Factors.ORG_PREFIX)).findFirst()
                            .ifPresent(a -> context.getClaims().claim("org",
                                    a.substring(Factors.ORG_PREFIX.length())));
                    // OIDC `sid` (id token only): identifies THIS OP session so back-channel logout can
                    // target the exact session on expiry/logout, not every session of the subject. Record
                    // the client as a participant of this session (the token endpoint has no HTTP session,
                    // so the sid→clients map is captured here for the termination listener to fan out to).
                    if (idToken) {
                        auth.stream().filter(a -> a.startsWith(Factors.SID_PREFIX)).findFirst()
                                .map(a -> a.substring(Factors.SID_PREFIX.length()))
                                .ifPresent(sid -> {
                                    context.getClaims().claim("sid", sid);
                                    backchannelIndex.record(sid, context.getRegisteredClient().getClientId(), username);
                                });
                    }
                }

                if (accessToken) {
                    // Use a mutable ArrayList, NOT Stream.toList()/List.of(): the JDBC authorization store
                    // re-serializes token claims with Jackson polymorphic typing, and its security
                    // PolymorphicTypeValidator rejects ImmutableCollections$ListN on read-back (token
                    // endpoint 500). ArrayList is on Spring Security's Jackson allow-list.
                    context.getClaims().claim("roles",
                            new ArrayList<>(user.getRoles().stream().map(RoleRef::getName).toList()));
                    // Bind the bearer to the issuing client so the admin gate can pin it to admin-console.
                    context.getClaims().claim("azp", context.getRegisteredClient().getClientId());
                }
            });
        };
    }

    /** Maps satisfied factor authorities to RFC 8176 Authentication Method References. */
    private List<String> amrValues(Set<String> authorities, long factorCount) {
        List<String> amr = new ArrayList<>();
        if (authorities.contains(Factors.PASSWORD)) amr.add("pwd");
        if (authorities.contains(Factors.TOTP)) amr.add("otp");
        if (authorities.contains(Factors.EMAIL) && !amr.contains("otp")) amr.add("otp");
        if (authorities.contains(Factors.FIDO2)) amr.add("hwk"); // hardware-backed / passkey
        if (factorCount >= 2) amr.add("mfa");
        return amr;
    }
}
