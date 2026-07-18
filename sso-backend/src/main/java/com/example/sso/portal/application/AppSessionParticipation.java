package com.example.sso.portal.application;

/**
 * One application a user's session (identified by {@code sid}) still holds a live SSO session/token with,
 * as published by an {@link AppSessionSource}. {@code oneClickLogoutSupported} is false when the app cannot
 * be logged out from the IdP without the browser (a SAML front-channel-only SP, or an OIDC client with no
 * back-channel logout URI) — the portal lists it but disables its sign-out button.
 *
 * <p>{@code sid} is a server-side routing key (which session holds the app); it is never sent to the browser
 * — the portal projects this to {@code AppSessionView} without it.
 */
public record AppSessionParticipation(AppType type, String appId, String sid, String name,
                                      boolean oneClickLogoutSupported) {
}
