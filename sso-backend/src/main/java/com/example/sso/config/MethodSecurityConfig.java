package com.example.sso.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * Enables method-level {@code @PreAuthorize} policies (PBAC) on top of URL-level RBAC.
 */
@Configuration
@EnableMethodSecurity
public class MethodSecurityConfig {
}
