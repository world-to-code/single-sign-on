package com.example.sso.portal;

/** Published after an application (OIDC client or SAML RP) is deleted, so other modules can drop references. */
public record ApplicationDeletedEvent(String appId) {
}
