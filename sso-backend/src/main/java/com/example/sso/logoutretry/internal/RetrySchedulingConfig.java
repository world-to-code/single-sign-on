package com.example.sso.logoutretry.internal;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on {@code @Scheduled} for the logout-retry sweep, co-located with the only scheduled bean so the
 * shared {@code config} module stays untouched. Also supplies the {@link Clock} the retry components use to
 * stamp due-times — injected rather than reading the system clock inline, so the schedule is deterministic
 * under test.
 */
@Configuration
@EnableScheduling
class RetrySchedulingConfig {

    @Bean
    Clock logoutRetryClock() {
        return Clock.systemUTC();
    }
}
