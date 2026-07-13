package com.example.sso.config.internal;

import com.example.sso.session.lifecycle.SessionLifecycle;

import com.example.sso.portal.access.AppAssignmentFilter;
import com.example.sso.portal.binding.AdminConsoleConfigService;

import com.example.sso.audit.AuditService;
import com.example.sso.authpolicy.factor.Factors;
import com.example.sso.ratelimit.AuthRateLimitFilter;
import com.example.sso.oidc.AdminPortalSeeder;
import com.example.sso.oidc.ConsentPage;
import com.example.sso.organization.OrganizationAuthorization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.user.account.UserService;
import com.example.sso.security.AdminElevationFilter;
import com.example.sso.security.PolicyIpAccessFilter;
import com.example.sso.security.HostOrgResolver;
import com.example.sso.auth.LoginResolutionScopeFilter;
import com.example.sso.security.OrgContextFilter;
import com.example.sso.security.OrgDrillInFilter;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.security.SessionIntegrityFilter;
import com.example.sso.security.TenantSessionHostGuard;
import com.example.sso.security.TenantUnknownSubdomainGuard;
import com.example.sso.session.policy.UserSessionPolicy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.webauthn.authentication.WebAuthnAuthenticationFilter;
import org.springframework.security.web.webauthn.registration.PublicKeyCredentialCreationOptionsRepository;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.security.SpringSessionBackedSessionRegistry;

import java.time.Duration;
import java.util.Set;

/**
 * Default application security for the React SPA + session model.
 *
 * <p>"Fully authenticated" is policy-driven: the {@code MFA_COMPLETE} authority is granted
 * once the user's authentication policy is satisfied (see the auth API + policy engine), so
 * protected APIs require {@code MFA_COMPLETE} (admin endpoints additionally require a fresh
 * admin-console elevation token and per-endpoint {@code @RequirePermission}).
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
     * Tracks live sessions per principal for the per-policy "max concurrent sessions" control. Backed by
     * Spring Session (Redis): sessions auto-register (indexed by principal), so no manual registration is
     * needed, and {@code SessionInformation.expireNow()} deletes the Redis session — which fires a
     * {@code SessionDeletedEvent} the back-channel-logout listener reacts to. {@code SessionLifecycle}
     * (SessionManagerImpl) only expires the oldest overflow; {@link SessionIntegrityFilter} enforces expiry.
     */
    @Bean
    SessionRegistry sessionRegistry(FindByIndexNameSessionRepository<? extends Session> sessions) {
        return new SpringSessionBackedSessionRegistry<>(sessions);
    }

    /**
     * WebAuthn allowed origins, tenant-aware: the configured platform/dev origins PLUS the current request's
     * own origin when its host is a base domain or a live tenant — so a passkey ceremony at a tenant subdomain
     * validates without pre-registering every one. Shared by the app chain's {@code http.webAuthn(..)} and the
     * {@code Webauthn4JRelyingPartyOperations} assertion validator.
     */
    @Bean
    Set<String> webAuthnAllowedOrigins(HostOrgResolver hostOrgResolver,
            @Value("${sso.webauthn.allowed-origins:http://localhost:9000,http://localhost:5173}") Set<String> configured) {
        return new TenantAwareWebAuthnOrigins(configured, hostOrgResolver);
    }

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    SecurityFilterChain appSecurityFilterChain(
            HttpSecurity http, AuthRateLimitFilter authRateLimitFilter, LoginResolutionScopeFilter loginScopeFilter,
            SessionIntegrityFilter sessionIntegrityFilter, OrgContext orgContext, HostOrgResolver hostOrgResolver,
            OrganizationService organizations, OrganizationAuthorization orgAuthorization, UserService users,
            UserSessionPolicy userSessionPolicy,
            JwtDecoder jwtDecoder,
            AdminConsoleConfigService adminConsoleConfig, AuditService audit,
            PublicKeyCredentialCreationOptionsRepository creationOptionsRepository,
            @Value("${sso.issuer}") String issuer,
            @Value("${sso.webauthn.rp-id:localhost}") String rpId,
            @Value("${sso.webauthn.rp-name:Mini SSO}") String rpName,
            @Qualifier("webAuthnAllowedOrigins") Set<String> allowedOrigins)
            throws Exception {
        CsrfTokenRequestAttributeHandler csrfRequestHandler = new CsrfTokenRequestAttributeHandler();

        http
                // PRIMARY passwordless passkey login (Spring Security WebAuthn module). /webauthn/register
                // (built-in page) registers passkeys; /login/webauthn authenticates with one and grants
                // FACTOR_WEBAUTHN, which the policy engine treats as the FIDO2 factor. The creation-options
                // repository stores only the challenge so the ceremony survives the Redis session store.
                .webAuthn(webAuthn -> webAuthn
                        .rpName(rpName)
                        .rpId(rpId)
                        .allowedOrigins(allowedOrigins)
                        .creationOptionsRepository(creationOptionsRepository)
                        .disableDefaultRegistrationPage(true) // the React SPA drives the passkey UI
                        // Rotate the session id when /login/webauthn authenticates a passkey (session-fixation
                        // protection): the built-in filter defaults to NullAuthenticatedSessionStrategy, so
                        // without this the rotation would be deferred to /api/auth/complete — a window in which
                        // a fixed pre-auth session could be completed by whoever holds it. The password/factor
                        // paths already rotate in-request via FactorAuthorizationService#establish.
                        .withObjectPostProcessor(new ObjectPostProcessor<WebAuthnAuthenticationFilter>() {
                            @Override
                            public <O extends WebAuthnAuthenticationFilter> O postProcess(O filter) {
                                filter.setSessionAuthenticationStrategy(new ChangeSessionIdAuthenticationStrategy());
                                return filter;
                            }
                        }))
                .authorizeHttpRequests(auth -> auth
                        // Passkey REGISTRATION (self-service "My Passkeys") requires a completed login —
                        // otherwise an identified-but-unauthenticated session could plant a passkey on the
                        // account. Enroll-at-login registers via the gated /api/auth/factors/FIDO2 path instead.
                        .requestMatchers("/webauthn/register", "/webauthn/register/options")
                        .access(AuthorityAuthorizationManager.hasAuthority(Factors.MFA_COMPLETE))
                        // Public auth/bootstrap + passwordless passkey login endpoints.
                        .requestMatchers("/api/auth/**", "/webauthn/**", "/login/webauthn").permitAll()
                        // Onboarding invitation redeem: the invitee has no credentials yet; the single-use,
                        // high-entropy, time-boxed token is the authorization (CSRF + rate-limit still apply).
                        .requestMatchers("/api/onboarding/**").permitAll()
                        .requestMatchers("/actuator/health/**", "/error").permitAll()
                        // SAML metadata is public; the SSO endpoint requires a completed policy. Single
                        // Logout is public — the SP signature is the real check, and it only ends the
                        // caller's own session (SameSite=Lax blocks cross-site POST logout-CSRF).
                        .requestMatchers("/saml2/idp/metadata", "/saml2/idp/slo", "/saml2/idp/slo/**").permitAll()
                        // SPA shell + static assets (the SPA itself gates content via /api/auth/session).
                        .requestMatchers(HttpMethod.GET, "/", "/index.html", "/favicon.ico", "/assets/**",
                                "/login", "/signup", "/activate", "/set-password", "/product", "/integrations", "/security", "/how-it-works",
                                "/stepup", "/apps", "/passkeys", "/applications", "/users", "/groups", "/auth-policies", "/clients",
                                "/relying-parties", "/scim-tokens", "/session-policy",
                                "/audit", "/profile",
                                // Admin console SPA shell (the OIDC flow + admin API still enforce auth).
                                "/admin", "/admin/**").permitAll()
                        .requestMatchers("/saml2/idp/sso", "/saml2/idp/sso/init")
                        .access(AuthorityAuthorizationManager.hasAuthority(Factors.MFA_COMPLETE))
                        // The custom OIDC consent page renders account data (the user's prior consent) mid-flow,
                        // so it demands a completed login — the same bar the /oauth2/authorize grant it feeds
                        // enforces (defence in depth: the normal flow always arrives here past that gate).
                        .requestMatchers(HttpMethod.GET, ConsentPage.URI)
                        .access(AuthorityAuthorizationManager.hasAuthority(Factors.MFA_COMPLETE))
                        // Console entry is an APP ASSIGNMENT, not a role: AppAssignmentFilter gates the
                        // admin-console token at /oauth2/authorize, the elevation filter requires that
                        // fresh token here, and every endpoint enforces its fine-grained
                        // @RequirePermission. The URL level only demands a completed login.
                        .requestMatchers("/api/admin/**")
                        .access(AuthorityAuthorizationManager.hasAuthority(Factors.MFA_COMPLETE))
                        .requestMatchers("/api/me", "/api/portal/**")
                        .access(AuthorityAuthorizationManager.hasAuthority(Factors.MFA_COMPLETE))
                        // Fail-secure: anything not explicitly public requires authentication.
                        .anyRequest().authenticated())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(csrfRequestHandler)
                        .ignoringRequestMatchers("/saml2/idp/sso", "/saml2/idp/slo"))
                .addFilterBefore(authRateLimitFilter, CsrfFilter.class)
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                // Bind the request's tenant context (org / platform) FIRST, so the session-policy consumers
                // that follow resolve the caller's ORG-scoped policy, not just the global default. Ordering is
                // explicit (each filter anchored on the previous) — the tenant's session controls depend on it.
                .addFilterAfter(new OrgContextFilter(orgContext), CsrfFilter.class)
                // Zero-Trust host allowlist on the app chain (twin of the OIDC chain's TenantHostFilter): an
                // unknown/suspended tenant subdomain ({slug}.base with no active org) 404s across the whole app
                // surface — SPA shell, static assets, /api/** — while the bare host and foreign/IP hosts pass.
                .addFilterAfter(new TenantUnknownSubdomainGuard(hostOrgResolver), OrgContextFilter.class)
                // During the LOGIN phase (before MFA completes, when OrgContext is not yet bound), scope user
                // resolution to the organization being signed into, so the password provider / factor-state /
                // lockout target the right tenant's account and never a same-named user in another org.
                .addFilterAfter(loginScopeFilter, OrgContextFilter.class)
                // Zero-Trust tenant↔session binding: a session bound to org A must not be honoured on a DIFFERENT
                // tenant's subdomain host (defence in depth over host-only cookie scoping). Runs right after the
                // org is bound; the apex and the platform/super-admin context are exempt.
                .addFilterAfter(new TenantSessionHostGuard(hostOrgResolver, orgContext, audit), OrgContextFilter.class)
                // Zero-Trust: re-verify session integrity (client binding + absolute lifetime + idle + reauth)
                // on every request — under the bound org, so a tenant's stricter session policy actually applies.
                .addFilterAfter(sessionIntegrityFilter, TenantSessionHostGuard.class)
                // Per-policy network (IP) access, post-authentication (also registered on the OIDC chain).
                .addFilterAfter(new PolicyIpAccessFilter(userSessionPolicy, audit), SessionIntegrityFilter.class)
                // Platform super-admin drill-in: an X-Org-Context header on /api/admin/** scopes the request
                // to one tenant (super-admin only; a tenant admin sending it is refused). Runs AFTER the
                // session-security filters so the super-admin's OWN session still follows its base/platform
                // policy; the rebind scopes only the admin CRUD that follows.
                .addFilterAfter(new OrgDrillInFilter(orgContext, organizations, orgAuthorization, users, audit),
                        PolicyIpAccessFilter.class)
                // RFC 9470 elevation gate: require a fresh admin-console bearer token on /api/admin/**.
                // Anchored AFTER the authorization filter so the session MFA_COMPLETE check
                // (and @RequirePermission) still run first — a non-admin gets 403 there, never the 401 challenge.
                .addFilterAfter(new AdminElevationFilter(jwtDecoder, issuer, AdminPortalSeeder.CLIENT_ID,
                        adminConsoleConfig, audit), AuthorizationFilter.class)
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
