package com.example.sso.config.internal;

import com.example.sso.audit.AuditService;
import com.example.sso.response.ResponseApiTokenFilter;
import com.example.sso.ratelimit.RateLimit;
import com.example.sso.ratelimit.RateLimits;
import com.example.sso.response.ResponseCaller;
import com.example.sso.security.HostOrgResolver;
import com.example.sso.security.TenantHostFilter;
import com.example.sso.tenancy.OrgContext;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.authorization.AuthenticatedAuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * Security for the machine response API: stateless, {@code client_credentials}-authenticated, and never
 * reachable with a browser session.
 *
 * <p>{@link TenantHostFilter} runs FIRST, and it is not decoration. The tenant is derived from the host here
 * exactly as it is on the OIDC chain, so an unknown or suspended host is refused before any token is looked
 * at, and the org bound for the request is the same one whose issuer must have signed that token. Deriving
 * the tenant from the token instead would make the client registration the only thing separating two
 * tenants; deriving it from the host makes the signing key a second.
 *
 * <p>Both filters are instantiated here rather than declared as {@code @Component}s — a component filter is
 * auto-registered on EVERY chain, which would let a response token authenticate against the main application
 * chain and turn a machine credential into a session.
 */
@Configuration
public class ResponseApiSecurityConfig {

    private static final String RESPONSE_BUDGET_NAMESPACE = "response";

    @Bean
    @Order(2)
    SecurityFilterChain responseApiSecurityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder,
            RegisteredClientRepository clients, ResponseCaller caller, AuditService audit,
            HostOrgResolver hostOrgResolver, OrgContext orgContext, RateLimits rateLimits,
            @Value("${sso.issuer}") String issuer,
            @Value("${sso.response.budget.actions}") long budgetActions,
            @Value("${sso.response.budget.window}") Duration budgetWindow) throws Exception {
        // Keyed per client inside the limiter, so one tenant's runaway detector cannot exhaust another's.
        RateLimit budget = rateLimits.named(RESPONSE_BUDGET_NAMESPACE, budgetActions, budgetWindow);
        ResponseApiTokenFilter tokenFilter =
                new ResponseApiTokenFilter(jwtDecoder, clients, caller, audit, budget, issuer);

        http
                .securityMatcher("/api/response/v1/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        // Plain "authenticated", NOT the app chain's MFA-completed manager: there is no user
                        // here to have completed a factor. WHAT the caller may do is decided per verb by the
                        // scope on each handler.
                        .anyRequest().access(AuthenticatedAuthorizationManager.authenticated()))
                .addFilterBefore(new TenantHostFilter(hostOrgResolver, orgContext), AuthorizationFilter.class)
                .addFilterAfter(tokenFilter, TenantHostFilter.class)
                .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }
}
