package com.example.sso.config.internal;

import com.example.sso.scim.ScimBearerTokenFilter;
import com.example.sso.scim.ScimTokenService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.authorization.AuthenticatedAuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * Security for the SCIM 2.0 server: stateless, bearer-token authenticated (machine-to-
 * machine), and explicitly NOT subject to the global user MFA factor requirement
 * (uses a plain {@code authenticated()} manager via {@code .access(...)}).
 */
@Configuration
public class ScimSecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain scimSecurityFilterChain(HttpSecurity http, ScimTokenService scimTokenService)
            throws Exception {
        // Instantiated here (NOT a @Component) so it runs only on this /scim/v2/** chain — a @Component
        // Filter would also be auto-registered on the main app chain, letting a SCIM token authenticate there.
        ScimBearerTokenFilter scimBearerTokenFilter = new ScimBearerTokenFilter(scimTokenService);

        http
                .securityMatcher("/scim/v2/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .anyRequest().access(AuthenticatedAuthorizationManager.authenticated()))
                .addFilterBefore(scimBearerTokenFilter, AuthorizationFilter.class)
                .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }
}
