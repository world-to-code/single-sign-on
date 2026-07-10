package com.example.sso.shared.error;

/** The account is temporarily locked (too many failed attempts); maps to HTTP 423. */
public class LockedException extends ApiException {

    public LockedException(String message) {
        super(ErrorCode.LOCKED, message);
    }

    private LockedException(String messageKey, Object[] messageArgs) {
        super(ErrorCode.LOCKED, messageKey, messageArgs);
    }

    /** Localized variant: {@code messageKey} is resolved against the message bundle at render time. */
    public static LockedException of(String messageKey, Object... messageArgs) {
        return new LockedException(messageKey, messageArgs);
    }
}
