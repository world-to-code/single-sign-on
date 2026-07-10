package com.example.sso.shared.web;

import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.LockedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.ProblemDetail;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The same {@link BadRequestException} carrying a message key must render a Korean {@code detail} under
 * an {@code Accept-Language: ko} request and an English one under {@code en} — proving the
 * {@code MessageSource} + locale wiring localizes RFC 7807 responses. Uses the real {@code messages}
 * bundle so a missing or mistranslated key fails the test.
 */
class GlobalExceptionHandlerI18nTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(messageSource());

    private static ResourceBundleMessageSource messageSource() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding("UTF-8");
        messageSource.setUseCodeAsDefaultMessage(true);
        messageSource.setFallbackToSystemLocale(false);
        return messageSource;
    }

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void rendersKoreanDetailUnderKoLocale() {
        LocaleContextHolder.setLocale(Locale.KOREAN);

        ProblemDetail problem = handler.handleApiException(
                BadRequestException.of("resource.memberType.unknown", "wizard"), null);

        assertThat(problem.getDetail()).isEqualTo("알 수 없는 멤버 유형: wizard");
    }

    @Test
    void rendersEnglishDetailUnderEnLocale() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);

        ProblemDetail problem = handler.handleApiException(
                BadRequestException.of("resource.memberType.unknown", "wizard"), null);

        assertThat(problem.getDetail()).isEqualTo("Unknown member type: wizard");
    }

    @Test
    void localizesTheAccountLockoutMessage() {
        // The account-lockout LockedException (423) is user-facing and actionable, so its detail must
        // localize too — not stay hardcoded English.
        LocaleContextHolder.setLocale(Locale.KOREAN);
        assertThat(handler.handleApiException(LockedException.of("auth.account.locked"), null).getDetail())
                .isEqualTo("계정이 일시적으로 잠겼습니다. 잠시 후 다시 시도하세요.");

        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertThat(handler.handleApiException(LockedException.of("auth.account.locked"), null).getDetail())
                .isEqualTo("Account is temporarily locked. Try again later.");
    }
}
