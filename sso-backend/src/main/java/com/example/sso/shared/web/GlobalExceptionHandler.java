package com.example.sso.shared.web;

import com.example.sso.shared.error.ApiException;
import com.example.sso.shared.error.ErrorCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

import java.net.URI;
import java.util.stream.Collectors;

/**
 * Translates domain and validation exceptions into a single RFC 7807 {@link ProblemDetail} response
 * shape, augmented with a machine-readable {@code code} from {@link ErrorCode}. Services throw
 * {@link ApiException} subtypes and stay free of web-layer (HTTP status) concerns.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ProblemDetail handleApiException(ApiException ex, WebRequest request) {
        return problem(ex.getCode(), ex.getMessage(), request);
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
        if (request instanceof ServletWebRequest servletRequest) {
            problem.setInstance(URI.create(servletRequest.getRequest().getRequestURI()));
        }
        return problem;
    }
}
