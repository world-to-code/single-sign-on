package com.example.sso.config.internal;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

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

    /**
     * Dedicated bounded pool for logout propagation (OIDC back-channel + SAML SLO fan-out), kept OFF the shared
     * {@code applicationTaskExecutor} so a slow/hung relying party cannot starve tenant onboarding/provisioning
     * (and vice versa). The queue is bounded — an overflow degrades to running the send on the caller (the Redis
     * event-listener thread) rather than dropping a logout or growing memory without bound.
     */
    @Bean
    TaskExecutor logoutPropagationExecutor(
            @Value("${sso.logout.propagation.executor.core-pool-size}") int corePoolSize,
            @Value("${sso.logout.propagation.executor.max-pool-size}") int maxPoolSize,
            @Value("${sso.logout.propagation.executor.queue-capacity}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("logout-propagation-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
