/**
 * Named interface: this product's SAML SERVICE-PROVIDER role — the mechanics of federating TO an upstream IdP,
 * as opposed to the identity-provider role the rest of the module plays for downstream SPs.
 *
 * <p>Deliberately stateless and tenancy-free: it knows SAML, not connections. The {@code federation} module owns
 * the provider registry, the identity resolution and the endpoints, and passes what it has resolved down here.
 * Keeping the dependency one-way ({@code federation → saml}) is what stops the two modules cycling.
 */
@NamedInterface("inbound")
package com.example.sso.saml.inbound;

import org.springframework.modulith.NamedInterface;
