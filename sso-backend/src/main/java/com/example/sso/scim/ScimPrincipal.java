package com.example.sso.scim;

import java.util.UUID;

/**
 * The result of authenticating a SCIM bearer token: the tenant the token belongs to, or {@code null} for a
 * global/platform token. The SCIM filter binds this org for the request so provisioning is confined to it.
 */
public record ScimPrincipal(UUID orgId) {
}
