package com.example.sso.mfa.internal.application;

import com.example.sso.mfa.SmsSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the {@link LoggingSmsSender} as the default {@link SmsSender} ONLY when the application defines no
 * other one. A deployment wires a real gateway simply by declaring its own {@code SmsSender} bean, which then
 * takes over with no collision to resolve. Kept as a {@code @Bean} method (not a component-scanned
 * {@code @Component}) because {@code @ConditionalOnMissingBean} is only reliable at the bean-method level.
 */
@Configuration
class MfaSmsConfig {

    @Bean
    @ConditionalOnMissingBean(SmsSender.class)
    SmsSender loggingSmsSender(@Value("${sso.sms-otp.log-code:false}") boolean logCode) {
        return new LoggingSmsSender(logCode);
    }
}
