package com.example.sso.shared.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * Stable, machine-readable error codes. Each carries the HTTP status it maps to; the code name is
 * emitted in the common error response ({@code ProblemDetail} "code" property) so clients can branch
 * on the code rather than on the human-readable message or the numeric status alone.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    NOT_FOUND(HttpStatus.NOT_FOUND),
    BAD_REQUEST(HttpStatus.BAD_REQUEST),
    CONFLICT(HttpStatus.CONFLICT),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST);

    private final HttpStatus status;
}
