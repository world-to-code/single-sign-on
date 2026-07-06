package com.example.sso.config.internal;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Enables {@code @Async} execution (Boot supplies the {@code applicationTaskExecutor}). Used by tenant
 * onboarding to provision + email off the request thread, so the create call returns immediately.
 */
@Configuration
@EnableAsync
public class AsyncConfig {
}
