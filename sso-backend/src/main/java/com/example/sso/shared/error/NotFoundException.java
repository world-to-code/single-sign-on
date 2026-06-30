package com.example.sso.shared.error;

/** A requested resource does not exist (maps to HTTP 404). */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
