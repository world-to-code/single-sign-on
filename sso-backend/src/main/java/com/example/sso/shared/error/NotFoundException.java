package com.example.sso.shared.error;

/** A requested resource does not exist (maps to HTTP 404). */
public class NotFoundException extends ApiException {

    public NotFoundException(String message) {
        super(ErrorCode.NOT_FOUND, message);
    }

    private NotFoundException(String messageKey, Object[] args) {
        super(ErrorCode.NOT_FOUND, messageKey, args);
    }

    /** Localized variant: {@code messageKey} is resolved against the MessageSource at render time. */
    public static NotFoundException of(String messageKey, Object... args) {
        return new NotFoundException(messageKey, args);
    }
}
