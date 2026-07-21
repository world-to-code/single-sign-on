package com.example.sso.shared.web;

import com.example.sso.shared.error.NotFoundException;
import com.example.sso.shared.error.UnauthorizedException;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.LockedException;
import org.junit.jupiter.api.AfterEach;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

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
    void theProblemTraceIdIsTheRequestsBoundTraceIdAndStableAcrossCalls() {
        // The seam: the traceId in the error response must equal the id the access-log filter bound for this
        // request (RequestTrace.ATTRIBUTE), so a client quoting it finds the request in the logs — and it must
        // not change between two errors on the same request.
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/api/x");
        servletRequest.setAttribute(RequestTrace.ATTRIBUTE, "0af7651916cd43dd8448eb211c80319c");
        ServletWebRequest request = new ServletWebRequest(servletRequest);

        ProblemDetail forbidden = handler.handleAccessDenied(new AccessDeniedException("no"), request);
        ProblemDetail badRequest =
                handler.handleApiException(BadRequestException.of("resource.memberType.unknown", "x"), request);

        assertThat(forbidden.getProperties()).containsEntry("traceId", "0af7651916cd43dd8448eb211c80319c");
        assertThat(badRequest.getProperties()).containsEntry("traceId", "0af7651916cd43dd8448eb211c80319c");
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

    /**
     * The bare 401 changed shape: it used to carry the literal "Unauthorized", and now resolves a shared key.
     * Every non-revealing 401 in the codebase goes through this no-arg constructor, so if the key were wrong
     * the whole class of them would silently render the raw key string to users.
     */
    @Test
    void theBare401ResolvesTheSharedKeyInBothLanguages() {
        ServletWebRequest request = new ServletWebRequest(new MockHttpServletRequest("GET", "/api/x"));
        LocaleContextHolder.setLocale(Locale.KOREAN);
        assertThat(handler.handleApiException(new UnauthorizedException(), request).getDetail())
                .isEqualTo("인증이 필요합니다.");

        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertThat(handler.handleApiException(new UnauthorizedException(), request).getDetail())
                .isEqualTo("Authentication is required.");
    }

    /** NotFoundException only just gained the key factory, and it holds the most migrated sites. */
    @Test
    void aNotFoundKeyIsLocalized() {
        ServletWebRequest request = new ServletWebRequest(new MockHttpServletRequest("GET", "/api/x"));
        LocaleContextHolder.setLocale(Locale.KOREAN);
        assertThat(handler.handleApiException(NotFoundException.of("user.notFound"), request).getDetail())
                .isEqualTo("사용자를 찾을 수 없습니다");
    }
}
