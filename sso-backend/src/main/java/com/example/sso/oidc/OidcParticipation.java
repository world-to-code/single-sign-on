package com.example.sso.oidc;

/**
 * One OIDC client a user's session (identified by {@code sid}) still holds a token with, surfaced in the
 * user portal's "active app sessions" view. {@code backChannelLogoutSupported} is false when the client has
 * no back-channel logout URI configured — the portal shows it but cannot offer a one-click IdP-side logout.
 */
public record OidcParticipation(String sid, String clientId, String name, boolean backChannelLogoutSupported) {
}
