package com.example.sso.oidc;

/**
 * OIDC Back-Channel Logout 1.0 protocol constants, shared across the module boundary (the provider
 * metadata is advertised from the config module; the logout_token is built and clients are looked up in
 * the oidc module). Keeps these wire strings out of scattered literals.
 */
public final class BackChannelLogout {

    /** Discovery metadata flags (advertised at {@code /.well-known/openid-configuration}). */
    public static final String METADATA_SUPPORTED = "backchannel_logout_supported";
    public static final String METADATA_SESSION_SUPPORTED = "backchannel_logout_session_supported";

    /** The logout_token's {@code events} member and the required back-channel-logout event type URI. */
    public static final String EVENTS_CLAIM = "events";
    public static final String EVENT_TYPE = "http://schemas.openid.net/event/backchannel-logout";

    /** {@code RegisteredClient} custom {@code ClientSettings} keys (persist in the client_settings JSON). */
    public static final String CLIENT_SETTING_URI = "settings.client.backchannel-logout-uri";
    public static final String CLIENT_SETTING_SESSION_REQUIRED = "settings.client.backchannel-logout-session-required";

    private BackChannelLogout() {
    }
}
