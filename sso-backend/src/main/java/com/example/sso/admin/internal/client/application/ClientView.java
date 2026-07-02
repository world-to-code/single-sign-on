package com.example.sso.admin.internal.client.application;

import com.example.sso.admin.internal.client.domain.OAuth2RegisteredClientEntity;

/**
 * Admin view of a registered OAuth2/OIDC client.
 */
public record ClientView(String id, String clientId, String clientName,
                         String scopes, String grantTypes, String redirectUris, String initiateLoginUri) {

    public static ClientView of(OAuth2RegisteredClientEntity clientEntity) {
        return new ClientView(clientEntity.getId(), clientEntity.getClientId(), clientEntity.getClientName(),
                clientEntity.getScopes(), clientEntity.getAuthorizationGrantTypes(),
                clientEntity.getRedirectUris(), clientEntity.getInitiateLoginUri());
    }

}
