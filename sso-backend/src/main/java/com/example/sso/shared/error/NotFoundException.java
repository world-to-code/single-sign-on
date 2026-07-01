package com.example.sso.shared.error;

/** A requested resource does not exist (maps to HTTP 404). */
public class NotFoundException extends ApiException {
    public NotFoundException(String message) {
        super(ErrorCode.NOT_FOUND, message);
    }
}
