package com.example.sso.shared.error;

/** The request is malformed or violates a domain rule (maps to HTTP 400). */
public class BadRequestException extends ApiException {

    public BadRequestException(String message) {
        super(ErrorCode.BAD_REQUEST, message);
    }

    private BadRequestException(String messageKey, Object[] messageArgs) {
        super(ErrorCode.BAD_REQUEST, messageKey, messageArgs);
    }

    /** Localized variant: {@code messageKey} is resolved against the message bundle at render time. */
    public static BadRequestException of(String messageKey, Object... messageArgs) {
        return new BadRequestException(messageKey, messageArgs);
    }
}
