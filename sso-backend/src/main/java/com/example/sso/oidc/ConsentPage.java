package com.example.sso.oidc;

/**
 * Location of the IdP's server-rendered OAuth2 authorization-consent screen.
 *
 * <p>Single source of truth shared by the authorization server (which points the authorization
 * endpoint at this URI) and the controller that renders it, so the path is never duplicated.
 */
public final class ConsentPage {

    /** URI of the custom consent page; also the {@code @GetMapping} of the rendering controller. */
    public static final String URI = "/oauth2/consent";

    private ConsentPage() {
    }
}
