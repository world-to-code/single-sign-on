package com.example.sso.config.internal;

import com.example.sso.admin.AdminPortalSettingsService;
import com.example.sso.authpolicy.Factors;
import com.example.sso.ratelimit.AuthRateLimitFilter;
import com.example.sso.oidc.AdminPortalSeeder;
import com.example.sso.security.AdminElevationFilter;
import com.example.sso.security.SessionIntegrityFilter;
import com.example.sso.security.SessionMetadataCleanupListener;
import com.example.sso.session.SessionMetadataStore;
import jakarta.servlet.http.HttpSessionListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationEventPublisher;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.authorization.SpringAuthorizationEventPublisher;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

import java.time.Duration;
import java.util.Set;

/**
 * Default application security for the React SPA + session model.
 *
 * <p>"Fully authenticated" is policy-driven: the {@code MFA_COMPLETE} authority is granted
 * once the user's authentication policy is satisfied (see the auth API + policy engine), so
 * protected APIs require {@code MFA_COMPLETE} (admin endpoints also require ROLE_ADMIN).
 * Page routes are public; the SPA gates itself via {@code /api/auth/session}.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    /** Publishes authorization granted/denied events so denials can be audited (Zero-Trust observability). */
    @Bean
    AuthorizationEventPublisher authorizationEventPublisher(ApplicationEventPublisher publisher) {
        return new SpringAuthorizationEventPublisher(publisher);
    }

    /**
     * Tracks live sessions per principal for the per-policy "max concurrent sessions" control. Our
     * custom JSON login flow does not run Spring's {@code ConcurrentSessionControlAuthenticationStrategy},
     * so {@code AuthApiController} registers sessions + expires the oldest overflow manually, and
     * {@link SessionIntegrityFilter} enforces the resulting expiry on the next request.
     */
    @Bean
    SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    /**
     * Bridges servlet session lifecycle events into Spring Security so the {@link SessionRegistry}
     * sees session creation/destruction (e.g. removing entries when a session is invalidated/expires).
     */
    @Bean
    ServletListenerRegistrationBean<HttpSessionEventPublisher> httpSessionEventPublisher() {
        return new ServletListenerRegistrationBean<>(new HttpSessionEventPublisher());
    }

    /**
     * Evicts a session's device metadata from the {@link SessionMetadataStore} when the container
     * destroys the session (logout / expiry / invalidation), so the self-service "My Profile"
     * sessions list never shows dead sessions. Registered like the {@code HttpSessionEventPublisher}.
     */
    @Bean
    ServletListenerRegistrationBean<HttpSessionListener> sessionMetadataCleanupListener(SessionMetadataStore store) {
        return new ServletListenerRegistrationBean<>(new SessionMetadataCleanupListener(store));
    }

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    SecurityFilterChain appSecurityFilterChain(
            HttpSecurity http, AuthRateLimitFilter authRateLimitFilter,
            SessionIntegrityFilter sessionIntegrityFilter, JwtDecoder jwtDecoder,
            AdminPortalSettingsService adminPortalSettingsService,
            @Value("${sso.issuer}") String issuer,
            @Value("${sso.webauthn.rp-id:localhost}") String rpId,
            @Value("${sso.webauthn.rp-name:Mini SSO}") String rpName,
            @Value("${sso.webauthn.allowed-origins:http://localhost:9000,http://localhost:5173}") Set<String> allowedOrigins)
            throws Exception {
        CsrfTokenRequestAttributeHandler csrfRequestHandler = new CsrfTokenRequestAttributeHandler();

        http
                // PRIMARY passwordless passkey login (Spring Security WebAuthn module). /webauthn/register
                // (built-in page) registers passkeys; /login/webauthn authenticates with one and grants
                // FACTOR_WEBAUTHN, which the policy engine treats as the FIDO2 factor.
                .webAuthn(webAuthn -> webAuthn
                        .rpName(rpName)
                        .rpId(rpId)
                        .allowedOrigins(allowedOrigins)
                        .disableDefaultRegistrationPage(true)) // the React SPA drives the passkey UI
                .authorizeHttpRequests(auth -> auth
                        // Passkey REGISTRATION (self-service "My Passkeys") requires a completed login —
                        // otherwise an identified-but-unauthenticated session could plant a passkey on the
                        // account. Enroll-at-login registers via the gated /api/auth/factors/FIDO2 path instead.
                        .requestMatchers("/webauthn/register", "/webauthn/register/options")
                        .access(AuthorityAuthorizationManager.hasAuthority(Factors.MFA_COMPLETE))
                        // Public auth/bootstrap + passwordless passkey login endpoints.
                        .requestMatchers("/api/auth/**", "/webauthn/**", "/login/webauthn").permitAll()
                        .requestMatchers("/actuator/health/**", "/error").permitAll()
                        // SAML metadata is public; the SSO endpoint requires a completed policy.
                        .requestMatchers("/saml2/idp/metadata").permitAll()
                        // SPA shell + static assets (the SPA itself gates content via /api/auth/session).
                        .requestMatchers(HttpMethod.GET, "/", "/index.html", "/favicon.ico", "/assets/**",
                                "/login", "/stepup", "/apps", "/passkeys", "/applications", "/users", "/groups", "/auth-policies", "/clients",
                                "/relying-parties", "/scim-tokens", "/session-policy", "/ip-ranges",
                                "/audit", "/profile",
                                // Admin console SPA shell (the OIDC flow + admin API still enforce auth).
                                "/admin", "/admin/**").permitAll()
                        .requestMatchers("/saml2/idp/sso", "/saml2/idp/sso/init")
                        .access(AuthorityAuthorizationManager.hasAuthority(Factors.MFA_COMPLETE))
                        .requestMatchers("/api/admin/**")
                        .access(AuthorizationManagers.allOf(
                                AuthorizationManagers.anyOf(
                                        AuthorityAuthorizationManager.hasRole("ADMIN"),
                                        AuthorityAuthorizationManager.hasRole("GROUP_ADMIN")),
                                AuthorityAuthorizationManager.hasAuthority(Factors.MFA_COMPLETE)))
                        .requestMatchers("/api/me", "/api/portal/**")
                        .access(AuthorityAuthorizationManager.hasAuthority(Factors.MFA_COMPLETE))
                        // Fail-secure: anything not explicitly public requires authentication.
                        .anyRequest().authenticated())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(csrfRequestHandler)
                        .ignoringRequestMatchers("/saml2/idp/sso"))
                .addFilterBefore(authRateLimitFilter, CsrfFilter.class)
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                // Zero-Trust: re-verify session integrity (client binding + absolute lifetime) on every request.
                .addFilterAfter(sessionIntegrityFilter, CsrfFilter.class)
                // RFC 9470 elevation gate: require a fresh admin-console bearer token on /api/admin/**.
                // Anchored AFTER the authorization filter so the session ROLE_ADMIN + MFA_COMPLETE check
                // (and @PreAuthorize) still run first — a non-admin gets 403 there, never the 401 challenge.
                .addFilterAfter(new AdminElevationFilter(jwtDecoder, issuer, AdminPortalSeeder.CLIENT_ID,
                        adminPortalSettingsService), AuthorizationFilter.class)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .exceptionHandling(ex -> ex
                        .defaultAuthenticationEntryPointFor(
                                new LoginUrlAuthenticationEntryPoint("/login"),
                                new MediaTypeRequestMatcher(MediaType.TEXT_HTML))
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
                                        + "object-src 'none'; base-uri 'self'; frame-ancestors 'none'")));
        return http.build();
    }
}
