package com.example.sso.shared.web;

import com.example.sso.shared.error.ApiException;
import com.example.sso.shared.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

import java.net.URI;
import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Translates domain and validation exceptions into a single RFC 7807 {@link ProblemDetail} response
 * shape, augmented with a machine-readable {@code code} from {@link ErrorCode} and a {@code traceId}.
 * Services throw {@link ApiException} subtypes and stay free of web-layer (HTTP status) concerns.
 *
 * <p>An {@code ApiException} carrying a {@code messageKey} is localized against the {@link MessageSource}
 * using the request locale; otherwise its verbatim message is used. Method-security denials
 * ({@code AccessDeniedException}) are mapped here to a clean 403 {@code ProblemDetail} so they never fall
 * through to Boot's default error page (which would leak a stack trace). Deliberately no catch-all
 * {@code Exception} handler: framework web exceptions keep their own status mapping and a genuine 5xx must
 * not be masked as a 4xx here. Unexpected 5xx flow through Boot's default error path (hardened by
 * {@code server.error.include-stacktrace=never}); every {@code ProblemDetail} this advice builds carries a
 * {@code traceId}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final MessageSource messageSource;

    GlobalExceptionHandler(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @ExceptionHandler(ApiException.class)
    ProblemDetail handleApiException(ApiException ex, WebRequest request) {
        String detail = ex.getMessageKey() != null
                ? messageSource.getMessage(ex.getMessageKey(), ex.getMessageArgs(), LocaleContextHolder.getLocale())
                : ex.getMessage();
        return problem(ex.getCode(), detail, request);
    }

    /**
     * A method-security denial (an {@code @PreAuthorize}/{@code @Can…} check thrown from inside the invoked
     * controller — {@code AuthorizationDeniedException} is a subtype) propagates back to the dispatcher and,
     * without this, falls through to Boot's DEFAULT error page — which leaks a full stack {@code trace}. Map
     * it to the same clean, non-revealing {@link ProblemDetail} (403, {@code code}, {@code traceId}) as every
     * other error. This is a SPECIFIC handler, not a catch-all, so a genuine 5xx is never masked as a 403;
     * URL-level (filter) denials are still handled by the security chain, now trace-free via {@code server.error}.
     */
    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleAccessDenied(AccessDeniedException ex, WebRequest request) {
        return problem(ErrorCode.FORBIDDEN, "Access is denied.", request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException ex, WebRequest request) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return problem(ErrorCode.VALIDATION_FAILED, detail.isBlank() ? "Validation failed" : detail, request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleIllegalArgument(IllegalArgumentException ex, WebRequest request) {
        return problem(ErrorCode.BAD_REQUEST, ex.getMessage(), request);
    }

    /**
     * A UNIQUE-constraint violation that slipped past an app-level pre-check because a concurrent transaction
     * committed the same key first (e.g. two admins racing the same policy name or priority). Map it to a clean,
     * non-revealing 409 — the sequential path already returns the specific message from its pre-check. ONLY the
     * unique case (SQLState 23505) is a 409; every other integrity violation (FK, CHECK, NOT NULL) is a genuine
     * 5xx and is re-thrown so it is never masked as a client error.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex, WebRequest request) {
        if (!isUniqueViolation(ex)) {
            throw ex;
        }
        String detail = messageSource.getMessage("error.conflict.concurrent", null, LocaleContextHolder.getLocale());
        return problem(ErrorCode.CONFLICT, detail, request);
    }

    private boolean isUniqueViolation(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private ProblemDetail problem(ErrorCode code, String detail, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.getStatus(), detail);
        problem.setProperty("code", code.name());
        String traceId = newTraceId();
        problem.setProperty("traceId", traceId);
        if (request instanceof ServletWebRequest servletRequest) {
            problem.setInstance(URI.create(servletRequest.getRequest().getRequestURI()));
        }
        // These are client (4xx) errors, so log at DEBUG to stay out of normal logs while still letting
        // an operator correlate a traceId a user reports. The detail is omitted deliberately: it can echo
        // user-supplied input (potential PII).
        log.debug("API error traceId={} code={} status={}", traceId, code.name(), code.getStatus().value());
        return problem;
    }

    /** 12 hex chars (48 bits) — enough to disambiguate concurrent failures without a tracing dependency. */
    private static String newTraceId() {
        return String.format("%012x", ThreadLocalRandom.current().nextLong() & 0xFFFFFFFFFFFFL);
    }
}
