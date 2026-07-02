package com.example.sso.session;

import org.springframework.stereotype.Component;

/**
 * Derives a short, display-only "Browser on OS" label from a User-Agent string for the self-service
 * sessions list. Best-effort and heuristic — never used for any security decision.
 */
@Component
public class DeviceLabeler {

    private static final String UNKNOWN_DEVICE = "Unknown device";
    private static final String UNKNOWN_OS = "Unknown OS";

    public String label(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return UNKNOWN_DEVICE;
        }
        return browser(userAgent) + " on " + os(userAgent);
    }

    private String browser(String ua) {
        return ua.contains("Edg/") ? "Edge"
                : ua.contains("OPR/") || ua.contains("Opera") ? "Opera"
                : ua.contains("Firefox") ? "Firefox"
                : (ua.contains("Chrome") && !ua.contains("Chromium")) ? "Chrome"
                : (ua.contains("Safari") && !ua.contains("Chrome")) ? "Safari"
                : "Browser";
    }

    private String os(String ua) {
        return ua.contains("Windows") ? "Windows"
                : (ua.contains("Mac OS X") || ua.contains("Macintosh")) ? "macOS"
                : ua.contains("Android") ? "Android"
                : (ua.contains("iPhone") || ua.contains("iPad") || ua.contains("iOS")) ? "iOS"
                : ua.contains("Linux") ? "Linux"
                : UNKNOWN_OS;
    }
}
