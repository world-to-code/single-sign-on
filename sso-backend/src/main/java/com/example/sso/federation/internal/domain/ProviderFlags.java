package com.example.sso.federation.internal.domain;

/**
 * The protocol-neutral half of a provider's configuration, carried as one value so the per-protocol factories
 * stay readable instead of trailing four positional arguments that every one of them repeats.
 *
 * @param allowJitProvisioning whether a first-time federated user with no local account is provisioned
 * @param linkByVerifiedEmail  whether a first login with no link may claim an EXISTING account by verified email
 * @param enabled              a disabled provider is not offered on the login screen and refuses to start
 * @param presetId             the vendor preset this was created from, or null for a custom connection
 */
public record ProviderFlags(boolean allowJitProvisioning, boolean linkByVerifiedEmail, boolean enabled,
                            String presetId) {
}
