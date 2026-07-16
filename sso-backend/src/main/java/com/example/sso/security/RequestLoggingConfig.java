package com.example.sso.security;

import com.example.sso.tenancy.OrgContext;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the {@link RequestLoggingFilter} at order 0 — INSIDE Spring Security's {@code FilterChainProxy}
 * (order {@code -100}) and inside Micrometer's observation filter, so when it runs the {@code SecurityContext},
 * the bound {@link OrgContext} and the request's {@code traceId} are all available. Registered as a servlet
 * filter (not a {@code @Component}, which Boot would auto-register at the wrong order) so the order is explicit.
 */
@Configuration
public class RequestLoggingConfig {

    @Bean
    FilterRegistrationBean<RequestLoggingFilter> requestLoggingFilterRegistration(OrgContext orgContext) {
        FilterRegistrationBean<RequestLoggingFilter> registration =
                new FilterRegistrationBean<>(new RequestLoggingFilter(orgContext));
        registration.setOrder(0);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
