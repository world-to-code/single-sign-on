package com.example.sso.federation.internal.application;

import com.example.sso.saml.inbound.UpstreamIdp;

/**
 * An enabled SAML connection, resolved for a login. The SAML half is handed to the protocol as-is; the two
 * flags govern what may happen once an identity is proven, and stay with this module.
 */
record ResolvedSamlProvider(String alias, UpstreamIdp upstream, boolean jitProvisioningAllowed,
                            boolean linkByVerifiedEmail) {
}
