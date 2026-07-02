package com.example.sso.shared.error;

/** The account is temporarily locked (too many failed attempts); maps to HTTP 423. */
public class LockedException extends ApiException {
    public LockedException(String message) {
        super(ErrorCode.LOCKED, message);
    }
}
