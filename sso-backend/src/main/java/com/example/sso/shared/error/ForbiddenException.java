package com.example.sso.shared.error;

/** The caller is authenticated but not allowed to perform the action (maps to HTTP 403). */
public class ForbiddenException extends ApiException {
    public ForbiddenException(String message) {
        super(ErrorCode.FORBIDDEN, message);
    }

    private ForbiddenException(String messageKey, Object[] args) {
        super(ErrorCode.FORBIDDEN, messageKey, args);
    }

    /** Localized variant: {@code messageKey} is resolved against the MessageSource at render time. */
    public static ForbiddenException of(String messageKey, Object... args) {
        return new ForbiddenException(messageKey, args);
    }
}
