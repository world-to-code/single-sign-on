package com.example.sso.shared.error;

import lombok.Getter;

/**
 * Base for domain exceptions that carry a machine-readable {@link ErrorCode}. Services throw the
 * concrete subtypes and stay free of web-layer (HTTP status) concerns; {@code GlobalExceptionHandler}
 * maps every {@code ApiException} uniformly to the common error response using its code.
 */
@Getter
public abstract class ApiException extends RuntimeException {

    private final ErrorCode code;

    protected ApiException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }
}
