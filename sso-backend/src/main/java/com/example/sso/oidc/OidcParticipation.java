package com.example.sso.oidc;

/**
 * One OIDC client a user's session (identified by {@code sid}) still holds a token with, surfaced in the
 * user portal's "active app sessions" view. {@code registeredClientId} is the globally-unique internal id
 * (the opaque routing key the portal echoes back to log out this one client — never the per-tenant
 * {@code client_id}); {@code name} is the display name. {@code backChannelLogoutSupported} is false when the
 * client has no back-channel logout URI configured — the portal shows it but cannot offer a one-click logout.
 */
public record OidcParticipation(String sid, String registeredClientId, String name,
                                boolean backChannelLogoutSupported) {
}
