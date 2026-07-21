package com.example.sso.portal.internal.console.api;

import com.example.sso.portal.application.AppType;
import com.example.sso.shared.error.BadRequestException;
import jakarta.validation.constraints.NotBlank;

/**
 * Identifies the app to sign out of: its kind ({@code OIDC}/{@code SAML}) and protocol id (an OIDC
 * {@code client_id} or a SAML {@code entityId}). The {@code entityId} is a URL, so it travels in the body
 * rather than a path segment.
 */
public record AppSessionLogoutRequest(@NotBlank String type, @NotBlank String appId) {

    public AppType appType() {
        try {
            return AppType.valueOf(type);
        } catch (IllegalArgumentException e) {
            throw BadRequestException.of("portal.appType.unknown");
        }
    }
}
