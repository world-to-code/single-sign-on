package com.example.sso.federation;

/**
 * The protocol-specific half of a provider registration. Sealed because the set of protocols is closed
 * ({@link FederationProtocol}), which lets the write path switch exhaustively instead of carrying one flat
 * record whose fields are half-null and whose validity depends on a discriminator the compiler cannot see.
 */
public sealed interface ProviderConfig permits OidcConfig, SamlConfig {

    FederationProtocol protocol();
}
