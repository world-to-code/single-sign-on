package com.example.sso.federation;

import java.util.List;

/**
 * Per-tenant registry of upstream OIDC providers. Reads and writes are scoped to the ACTING tier (a tenant its
 * own providers, the platform super-admin the global ones) via a fail-closed read-guard; the client secret is
 * SecretCipher-encrypted at rest and never leaves the module in a view. The implementation stays
 * module-internal.
 */
public interface IdentityProviderService {

    /** The acting tier's own providers (a tenant never sees another tenant's, nor the platform's). */
    List<IdentityProviderView> list();

    /** One provider by its {@code alias} within the acting tier; 404 if absent. */
    IdentityProviderView get(String alias);

    /** Registers or updates (by {@code alias}) a provider in the acting tier; encrypts the secret before persist. */
    void save(IdentityProviderSpec spec);

    /** Removes the acting tier's provider with this {@code alias}; a no-op if it does not exist. */
    void delete(String alias);
}
