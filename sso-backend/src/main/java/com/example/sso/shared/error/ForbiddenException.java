package com.example.sso.shared.error;

/** The caller is authenticated but not allowed to perform the action (maps to HTTP 403). */
public class ForbiddenException extends ApiException {
    public ForbiddenException(String message) {
        super(ErrorCode.FORBIDDEN, message);
    }
}
