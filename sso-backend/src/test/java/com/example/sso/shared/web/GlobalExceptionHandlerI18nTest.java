package com.example.sso.shared.web;

import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.LockedException;
import org.junit.jupiter.api.AfterEach;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.ProblemDetail;

import java.sql.SQLException;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void aMethodSecurityDenialRendersACleanForbiddenProblemNotAStackTrace() {
        // A method-security denial must map to the same RFC-7807 ProblemDetail (403, code, traceId) as every
        // other error — never Boot's default error body, which leaks a full stack `trace`.
        var problem = handler.handleAccessDenied(new AccessDeniedException("Access Denied"), null);

        assertThat(problem.getStatus()).isEqualTo(403);
        assertThat(problem.getDetail()).isEqualTo("Access is denied."); // generic, non-revealing
        assertThat(problem.getProperties()).containsEntry("code", "FORBIDDEN").containsKey("traceId");
        assertThat(problem.getProperties()).doesNotContainKey("trace"); // no stack trace leaks
    }

    @Test
    void aUniqueConstraintRaceMapsToACleanLocalizedConflict() {
        // A concurrent write that lost the race to a unique index (SQLState 23505) becomes a non-revealing 409,
        // not a 500 — the sequential path already returns the specific pre-check message.
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        ProblemDetail problem = handler.handleDataIntegrityViolation(
                new DataIntegrityViolationException("dup", new SQLException("duplicate key", "23505")), null);

        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getProperties()).containsEntry("code", "CONFLICT").containsKey("traceId");
        assertThat(problem.getDetail()).isEqualTo("The change conflicts with an existing record. Please retry.");
    }

    @Test
    void aNonUniqueIntegrityViolationIsRethrownAndNotMaskedAsAConflict() {
        // A foreign-key (23503) or other integrity violation is a genuine 5xx bug — it must NOT be turned into a
        // 409, so the handler re-throws it to fall through to the framework's (trace-free) 500 handling.
        DataIntegrityViolationException fk =
                new DataIntegrityViolationException("fk", new SQLException("fk violation", "23503"));

        assertThatThrownBy(() -> handler.handleDataIntegrityViolation(fk, null)).isSameAs(fk);
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
