package com.example.sso.portal;

import com.example.sso.portal.AppType;

/**
 * A launchable application contributed by a protocol module (OIDC/SAML) to the portal. Each module
 * owns how it derives the display name and launch URL; the portal only aggregates and layers on the
 * per-app sign-on policy. Keeps the portal from depending on the {@code admin}/{@code saml} modules.
 */
public record ApplicationDescriptor(AppType type, String id, String name, String launchUrl, boolean system) {
}
