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
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

import java.net.URI;
import java.sql.SQLException;
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

    /** How many offending field names a validation refusal names before it stops listing them. */
    private static final int MAX_REPORTED_FIELDS = 10;

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
        return problem(ErrorCode.FORBIDDEN, localized("error.forbidden"), request);
    }

    /**
     * Bean validation writes its own message, in English, from the constraint's default ("must not be blank")
     * — so the raw binding result is untranslatable. The FIELDS are the part the reader needs and the part we
     * can hand over unchanged; the sentence around them comes from the bundle like every other refusal.
     *
     * <p>Capped: a collection field reports one error PER ELEMENT ({@code keys[0]}, {@code keys[1]}, …), all
     * distinct, so an oversized list would turn a refusal into a response many times the size of the request
     * that caused it. The cap keeps the naming useful for the first few and bounded for the rest.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException ex, WebRequest request) {
        String fields = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getField)
                .distinct()
                .limit(MAX_REPORTED_FIELDS)
                .collect(Collectors.joining(", "));
        String detail = fields.isBlank()
                ? localized("error.validation.failed.unnamed")
                : localized("error.validation.failed", fields);
        return problem(ErrorCode.VALIDATION_FAILED, detail, request);
    }

    /**
     * An {@code IllegalArgumentException} is an invariant violation written for a developer, so its message is
     * English prose that frequently quotes the offending input. It used to be handed to the user verbatim:
     * untranslated, and a non-revealing-errors violation whenever the input was somebody's address. The
     * message is withheld from both the user and the log, and the operator gets the throw SITE instead.
     *
     * <p>Logging the message was the obvious thing and it is wrong here: it quotes the offending input, so the
     * log inherits exactly what the response stopped carrying. Some of that input is secret — decoding a
     * malformed TOTP secret throws with the offending CHARACTER in the message — and all of it is attacker
     * chosen, which is how a newline in a username forges a second log line. The throw site says where to look
     * without repeating anything the caller supplied.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleIllegalArgument(IllegalArgumentException ex, WebRequest request) {
        log.debug("Mapped an IllegalArgumentException to 400, thrown at {}", throwSite(ex));
        return problem(ErrorCode.BAD_REQUEST, localized("error.badRequest"), request);
    }

    /** The frame that threw, as {@code Class.method(File:line)} — enough to find the invariant, with no input in it. */
    private String throwSite(Throwable ex) {
        StackTraceElement[] frames = ex.getStackTrace();
        return frames.length == 0 ? "an unknown frame" : frames[0].toString();
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
        // Observable at WARN (not the DEBUG the 4xx path uses): this downgrades what would otherwise be a 500 to a
        // 409, so a masked unique-constraint conflict stays visible to operators. SQLState only — the driver's
        // message echoes the offending key values (potential user input / PII), so it is deliberately not logged.
        log.warn("Mapped a unique-constraint violation (SQLState 23505) to 409 — a concurrent write lost the race");
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

    /**
     * Every 4xx detail resolves through the bundle, so the console never has to decide whether the string it
     * received is in the user's language. That was the actual defect: three of the five handlers built their
     * detail in English while the client (correctly) preferred the server's detail over its own copy.
     */
    private String localized(String key, Object... args) {
        return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
    }

    private ProblemDetail problem(ErrorCode code, String detail, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.getStatus(), detail);
        problem.setProperty("code", code.name());
        // The SAME trace id the request's log lines (access log) carry, so a user quoting it lets an operator
        // pull the whole request from the logs — no longer a throwaway per-response random value.
        String traceId = RequestTrace.of(request instanceof ServletWebRequest s ? s.getRequest() : null);
        problem.setProperty("traceId", traceId);
        if (request instanceof ServletWebRequest servletRequest) {
            problem.setInstance(URI.create(servletRequest.getRequest().getRequestURI()));
        }
        // Client (4xx) errors: the access-log line already records this request (with its status and traceId), so
        // keep the handler quiet at DEBUG. Detail is omitted — it can echo user-supplied input (potential PII).
        log.debug("API error traceId={} code={} status={}", traceId, code.name(), code.getStatus().value());
        return problem;
    }
}
