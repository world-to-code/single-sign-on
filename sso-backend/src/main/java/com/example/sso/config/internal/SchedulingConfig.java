package com.example.sso.config.internal;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * App-wide scheduling infrastructure. Enables {@code @Scheduled} once for the whole context and supplies the
 * single {@link Clock} the scheduled components (durable logout retry, durable session-termination retry) use to
 * stamp due-times — injected rather than reading the system clock inline, so those schedules stay deterministic
 * under test. Lives in the shared {@code config} module so a feature module's scheduled backstop depends on
 * common infra, not on another feature module happening to enable scheduling.
 */
@Configuration
@EnableScheduling
class SchedulingConfig {

    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }
}
