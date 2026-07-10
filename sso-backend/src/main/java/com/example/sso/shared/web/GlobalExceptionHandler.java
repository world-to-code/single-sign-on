package com.example.sso.shared.web;

import com.example.sso.shared.error.ApiException;
import com.example.sso.shared.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

import java.net.URI;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Translates domain and validation exceptions into a single RFC 7807 {@link ProblemDetail} response
 * shape, augmented with a machine-readable {@code code} from {@link ErrorCode} and a {@code traceId}.
 * Services throw {@link ApiException} subtypes and stay free of web-layer (HTTP status) concerns.
 *
 * <p>An {@code ApiException} carrying a {@code messageKey} is localized against the {@link MessageSource}
 * using the request locale; otherwise its verbatim message is used. Deliberately no catch-all
 * {@code Exception} handler: this app relies on method security, whose {@code AccessDeniedException}
 * must reach the security filter (403) rather than being masked as a 500 here, and framework web
 * exceptions must keep their own status mapping. Unexpected 5xx therefore flow through Boot's default
 * error path; every {@code ProblemDetail} this advice builds still carries a {@code traceId}.
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
