package com.example.sso.federation.internal.application;

import java.util.UUID;

/**
 * What a started SAML login must remember until the assertion comes back. Held server-side rather than in the
 * session because the ACS is a CROSS-SITE POST: the session cookie is {@code SameSite=Lax}, so it is simply not
 * sent, and everything the ACS needs — including which tenant this login belongs to — has to arrive by RelayState.
 */
record PendingSamlLogin(UUID orgId, String alias, String requestId, String spEntityId,
                        String acsUrl, String browserHandle) {
}
