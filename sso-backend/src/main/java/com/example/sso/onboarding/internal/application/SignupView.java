package com.example.sso.onboarding.internal.application;

/**
 * What the public self-service signup returns to an anonymous caller. {@code slug} is the (normalized)
 * organization subdomain, echoed so the SPA can show "check your email to finish {slug}". {@code workspaceHost}
 * is the organization's address ({@code {slug}.base}), populated only by activation so the SPA can link the new
 * admin straight to their tenant login; it is {@code null} at request time (nothing is created yet).
 */
public record SignupView(String slug, String workspaceHost) {
}
