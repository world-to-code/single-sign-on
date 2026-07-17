package com.example.sso.audit.internal.application;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the {@link AdminAuditInterceptor} on the admin API. Ordered FIRST (outermost) so its
 * {@code afterCompletion} runs even when an inner interceptor (e.g. the step-up gate) short-circuits the
 * request — a blocked privileged attempt is then still audited.
 */
@Configuration
@RequiredArgsConstructor
public class AuditWebConfig implements WebMvcConfigurer {

    private final AdminAuditInterceptor adminAuditInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminAuditInterceptor)
                .addPathPatterns("/api/admin/**")
                .order(Ordered.HIGHEST_PRECEDENCE);
    }
}
