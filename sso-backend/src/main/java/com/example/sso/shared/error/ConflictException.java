package com.example.sso.shared.error;

/** The request conflicts with current state, e.g. a duplicate (maps to HTTP 409). */
public class ConflictException extends ApiException {

    public ConflictException(String message) {
        super(ErrorCode.CONFLICT, message);
    }

    private ConflictException(String messageKey, Object[] messageArgs) {
        super(ErrorCode.CONFLICT, messageKey, messageArgs);
    }

    /** Localized variant: {@code messageKey} is resolved against the message bundle at render time. */
    public static ConflictException of(String messageKey, Object... messageArgs) {
        return new ConflictException(messageKey, messageArgs);
    }
}
