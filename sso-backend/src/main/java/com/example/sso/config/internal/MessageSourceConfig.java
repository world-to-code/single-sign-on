package com.example.sso.config.internal;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.util.List;
import java.util.Locale;

/**
 * Internationalizes user-facing error copy. The SPA renders RFC 7807 {@code detail} verbatim for 400
 * and 409, so those messages must arrive in the caller's language. The locale is taken from the
 * {@code Accept-Language} header, which the SPA always sends.
 *
 * <p>A caller that omits the header is a machine — SCIM provisioners, curl, the {@code scripts/}
 * live-flow verifiers — so the fallback is English, not Korean. Defaulting to Korean would hand a
 * Hangul {@code detail} to every integration that never asked for one.
 */
@Configuration
public class MessageSourceConfig {

    /**
     * Resolves message keys against {@code messages_{locale}.properties}. {@code useCodeAsDefaultMessage}
     * means an unmigrated key renders as the key itself rather than throwing, so a half-migrated codebase
     * stays valid. System-locale fallback is disabled so an {@code en} request never picks up {@code ko}
     * from the host JVM's default locale.
     */
    @Bean
    public MessageSource messageSource() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding("UTF-8");
        messageSource.setUseCodeAsDefaultMessage(true);
        messageSource.setFallbackToSystemLocale(false);
        return messageSource;
    }

    @Bean
    public LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver localeResolver = new AcceptHeaderLocaleResolver();
        localeResolver.setSupportedLocales(List.of(Locale.KOREAN, Locale.ENGLISH));
        localeResolver.setDefaultLocale(Locale.ENGLISH);
        return localeResolver;
    }
}
