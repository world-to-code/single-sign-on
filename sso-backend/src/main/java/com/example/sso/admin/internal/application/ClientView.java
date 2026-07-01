package com.example.sso.admin.internal.application;

/** Admin view of a registered OAuth2/OIDC client. */
public record ClientView(String id, String clientId, String clientName,
                         String scopes, String grantTypes, String redirectUris, String initiateLoginUri) {
}
