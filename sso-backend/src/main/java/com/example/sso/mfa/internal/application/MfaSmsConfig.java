package com.example.sso.mfa.internal.application;

import com.example.sso.mfa.SmsSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the {@link LoggingSmsSender} as the LAST-RESORT sender, always — not, as it once was, only when no
 * other {@code SmsSender} bean exists.
 *
 * <p>That condition worked while the logging stub was the only implementation and a deployment replaced it
 * wholesale. It stopped working the moment sending became per-tenant: {@code TenantSmsSender} is itself an
 * {@code SmsSender}, so the condition would suppress the very bean the tenant router falls back to when a
 * tenant has configured no gateway of its own. Both are registered now, and the router is {@code @Primary}, so
 * a consumer asking for an {@code SmsSender} gets the tenant-aware one.
 */
@Configuration
class MfaSmsConfig {

    @Bean
    SmsSender loggingSmsSender(@Value("${sso.sms-otp.log-code:false}") boolean logCode) {
        return new LoggingSmsSender(logCode);
    }
}
