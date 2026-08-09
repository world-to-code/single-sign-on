package com.example.sso.oidc;

import java.util.UUID;

/**
 * Revokes the OAuth2 grants a user's sessions handed to applications.
 *
 * <p>Ending a session is not ending the access it granted. Session termination deletes the IdP's session and
 * asks each relying party to drop its own, but the relying party still holds a REFRESH TOKEN, and refreshing
 * never passes back through {@code /oauth2/authorize} — so a disabled, locked or held account kept minting
 * access tokens for the whole refresh lifetime while every console said it was signed out. This is the other
 * half of revocation.
 *
 * <p>Implementation stays module-internal; the tenant argument is not optional, because a username is unique
 * only within an organization.
 */
public interface OidcAuthorizations {

    /**
     * Removes every stored authorization this user holds with applications of {@code orgId} ({@code null} =
     * the platform tier), and returns how many went.
     *
     * <p>Scoped by the OWNING TENANT OF THE CLIENT, since the authorization row itself carries no
     * organization — its tenant is whoever owns the application it was issued for.
     */
    int revokeForUser(String username, UUID orgId);
}
