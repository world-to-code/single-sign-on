package com.example.sso.config.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.annotation.AnnotationTemplateExpressionDefaults;

/**
 * Enables method-level {@code @PreAuthorize} policies (PBAC) on top of URL-level RBAC, plus
 * meta-annotation templating so composed security annotations (e.g.
 * {@link com.example.sso.shared.security.RequirePermission}) can substitute their attributes into
 * the underlying expression via {@code {placeholder}} syntax.
 */
@Configuration
@EnableMethodSecurity
public class MethodSecurityConfig {

    @Bean
    AnnotationTemplateExpressionDefaults annotationTemplateExpressionDefaults() {
        return new AnnotationTemplateExpressionDefaults();
    }
}
