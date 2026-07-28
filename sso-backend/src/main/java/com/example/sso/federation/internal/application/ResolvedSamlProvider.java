package com.example.sso.federation.internal.application;

import com.example.sso.saml.inbound.UpstreamIdp;

/**
 * An enabled SAML connection, resolved for a login. The SAML half is handed to the protocol as-is; the two
 * flags govern what may happen once an identity is proven, and stay with this module. {@code emailAttribute}
 * stays here too — it is read against the VERIFIED assertion's attributes, so it names the address a
 * just-in-time account is created under rather than anything the protocol needs to check.
 */
record ResolvedSamlProvider(String alias, UpstreamIdp upstream, String emailAttribute,
                            boolean jitProvisioningAllowed, boolean linkByVerifiedEmail) {
}
