package com.example.sso.oidc;

/**
 * Where the OAuth2 authorization-consent screen lives.
 *
 * <p>The screen is part of the SPA, like every other screen in this product — sign-in and MFA
 * included. The authorization endpoint redirects the resource owner to {@link #URI}, the SPA reads
 * its model from {@link #API}, and the user's selection posts straight back to
 * {@code /oauth2/authorize}. Nothing about OAuth requires the page to be server-rendered; it was,
 * once, only because a controller plus a template is the framework's default customization point,
 * and that one page cost a second i18n bundle, a second stylesheet and a second branding path.
 *
 * <p>{@link #URI} must stay OUTSIDE {@code /oauth2/}: the nginx edge proxies that whole prefix to
 * this backend, so a consent path under it would never reach the SPA shell.
 */
public final class ConsentPage {

    /** SPA route the authorization endpoint redirects the resource owner to. */
    public static final String URI = "/consent";

    /** JSON model behind that route: which client, which scopes, which were already granted. */
    public static final String API = "/api/oauth2/consent";

    private ConsentPage() {
    }
}
