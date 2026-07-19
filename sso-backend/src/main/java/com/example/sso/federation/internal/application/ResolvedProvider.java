package com.example.sso.federation.internal.application;

/**
 * A tenant's upstream provider resolved for the login flow, with the client secret DECRYPTED — module-internal
 * only, never returned across the module boundary. {@code scopes} is the space-separated request list.
 */
record ResolvedProvider(String alias, String issuerUri, String clientId, String clientSecret, String scopes,
                        boolean jitProvisioningAllowed,
                        boolean linkByVerifiedEmail) {
}
