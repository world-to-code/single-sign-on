package com.example.sso.shared.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the best-effort first-hop client IP: the first entry of {@code X-Forwarded-For} when set
 * (a single trusted proxy hop), otherwise the socket remote address. Centralizes the logic that was
 * duplicated across the auth controller and several filters.
 */
public final class ClientIp {

    private static final String X_FORWARDED_FOR = "X-Forwarded-For";

    public static String of(HttpServletRequest request) {
        String forwarded = request.getHeader(X_FORWARDED_FOR);
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private ClientIp() {
    }
}
