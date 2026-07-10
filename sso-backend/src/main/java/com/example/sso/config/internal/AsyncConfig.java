package com.example.sso.config.internal;

import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Enables {@code @Async} execution (Boot supplies the {@code applicationTaskExecutor}). Used by tenant
 * onboarding and baseline provisioning to run off the request thread, so the create call returns
 * immediately. A {@code void @Async} failure never reaches a caller, so the uncaught-exception handler
 * is what makes it observable.
 */
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return new LoggingAsyncUncaughtExceptionHandler();
    }
}
