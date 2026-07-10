package com.example.sso.shared.error;

import lombok.Getter;

/**
 * Base for domain exceptions that carry a machine-readable {@link ErrorCode}. Services throw the
 * concrete subtypes and stay free of web-layer (HTTP status) concerns; {@code GlobalExceptionHandler}
 * maps every {@code ApiException} uniformly to the common error response using its code.
 *
 * <p>An exception may carry a pre-rendered {@code message} (the legacy path) <em>or</em> an optional
 * {@code messageKey} plus {@code messageArgs} resolved against the {@code MessageSource} at render
 * time (the localized path). Both coexist so throw sites migrate to keys module by module without a
 * flag day: {@code GlobalExceptionHandler} prefers the key when present and falls back to the message.
 */
@Getter
public abstract class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final String messageKey;
    private final Object[] messageArgs;

    protected ApiException(ErrorCode code, String message) {
        super(message);
        this.code = code;
        this.messageKey = null;
        this.messageArgs = null;
    }

    /**
     * Localized variant: {@code messageKey} is resolved against the {@code MessageSource} using the
     * request locale. The key doubles as the exception message so stack traces stay meaningful even
     * without a {@code MessageSource} in scope.
     */
    protected ApiException(ErrorCode code, String messageKey, Object[] messageArgs) {
        super(messageKey);
        this.code = code;
        this.messageKey = messageKey;
        this.messageArgs = messageArgs;
    }
}
