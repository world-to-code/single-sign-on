package com.example.sso.branding.internal.application;

/**
 * The acting tier's branding for the admin editor. {@code configured} is false when the tier has no own row
 * (it inherits the platform/built-in default) — the other fields then carry that inherited default as a
 * starting point.
 */
public record BrandingView(boolean configured, String logoUrl, String accentColor, String productName) {
}
