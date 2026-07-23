package com.example.sso.federation.internal.application;

/**
 * One extra input a preset needs to build its issuer — e.g. Microsoft Entra's directory (tenant) id, or an
 * Okta domain. The {@code key} matches a {@code {key}} placeholder in the preset's issuer template; the
 * {@code label} and {@code placeholder} drive the console form field. Google needs none (a fixed issuer).
 */
public record FederationPresetField(String key, String label, String placeholder) {
}
